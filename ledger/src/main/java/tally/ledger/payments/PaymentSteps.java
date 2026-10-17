package tally.ledger.payments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tally.ledger.audit.AuditEntry;
import tally.ledger.audit.AuditWriter;
import tally.ledger.core.EntryLine;
import tally.ledger.core.LedgerRuleException;
import tally.ledger.core.PaymentState;
import tally.ledger.core.Posting;
import tally.ledger.core.Side;
import tally.ledger.core.TransactionKind;
import tally.ledger.failpoint.Failpoints;
import tally.ledger.holds.HoldService;
import tally.ledger.idempotency.IdempotencyStore;
import tally.ledger.payments.PaymentStore.Changes;
import tally.ledger.payments.PaymentStore.PaymentRow;
import tally.ledger.posting.LedgerTx;
import tally.ledger.posting.LedgerWriter;
import tally.ledger.posting.PostedTransaction;
import tally.ledger.posting.PostingCommand;
import tally.ledger.posting.StrategyRunner;
import tally.ledger.posting.TxWork;
import tally.platform.security.Caller;

@Component
public class PaymentSteps {

  private static final Logger log = LoggerFactory.getLogger(PaymentSteps.class);
  private static final Caller CALLER = Caller.job("orchestrator");
  private final StrategyRunner runner;
  private final PaymentStore payments;
  private final IdempotencyStore keys;
  private final HoldService holds;
  private final LedgerWriter writer;
  private final AuditWriter audit;
  private final Clock clock;

  public PaymentSteps(
      StrategyRunner runner,
      PaymentStore payments,
      IdempotencyStore keys,
      HoldService holds,
      LedgerWriter writer,
      AuditWriter audit,
      Clock clock) {
    this.runner = runner;
    this.payments = payments;
    this.keys = keys;
    this.holds = holds;
    this.writer = writer;
    this.audit = audit;
    this.clock = clock;
  }

  public Optional<PaymentRow> hold(PaymentRow payment) {
    String requestId = UUID.randomUUID().toString();
    return execute(
        payment,
        tx -> {
          Instant now = now();
          OptionalLong key = claim(tx.connection(), payment, "hold", now);
          if (key.isEmpty()) {
            return replay(tx.connection(), payment);
          }
          Savepoint work = tx.connection().setSavepoint();
          try {
            HoldService.Placed placed =
                holds.placeIn(
                    tx,
                    key.getAsLong(),
                    payment.sourceAccountId(),
                    payment.currency(),
                    payment.amount(),
                    now.plus(Duration.ofHours(1)),
                    entry(payment, "payment.hold", requestId),
                    CALLER);
            return transition(
                tx.connection(),
                payment,
                PaymentState.HELD,
                new Changes(placed.holdId(), null, null, null, null, null, null),
                null,
                now,
                placed.auditId());
          } catch (LedgerRuleException exception) {
            tx.connection().rollback(work);
            return failIn(tx.connection(), payment, exception.code().name(), now, requestId);
          }
        });
  }

  public Optional<PaymentRow> capture(PaymentRow payment) {
    String requestId = UUID.randomUUID().toString();
    return execute(
        payment,
        tx -> {
          Instant now = now();
          OptionalLong key = claim(tx.connection(), payment, "capture", now);
          if (key.isEmpty()) {
            return replay(tx.connection(), payment);
          }
          UUID clearing = internal(tx.connection(), "CLEARING", payment.currency());
          Savepoint work = tx.connection().setSavepoint();
          try {
            PostedTransaction posted =
                holds.captureIn(
                    tx,
                    key.getAsLong(),
                    payment.holdId(),
                    payment.amount(),
                    clearing,
                    false,
                    entry(payment, "payment.capture", requestId),
                    CALLER,
                    requestId);
            return transition(
                tx.connection(),
                payment,
                PaymentState.CAPTURED,
                new Changes(null, posted.id(), null, null, null, null, null),
                null,
                now,
                posted.auditId());
          } catch (LedgerRuleException exception) {
            tx.connection().rollback(work);
            return failIn(tx.connection(), payment, exception.code().name(), now, requestId);
          }
        });
  }

  public Optional<PaymentRow> markSubmitting(PaymentRow payment) {
    return change(payment, PaymentState.SUBMITTING, "payment.submit", unchanged(), null);
  }

  public Optional<PaymentRow> markUnknown(
      PaymentRow payment, int attempts, Instant nextAttemptAt, String detail) {
    if (payment.state() == PaymentState.UNKNOWN) {
      return execute(
          payment,
          tx -> {
            if (!payments.schedule(tx.connection(), payment.id(), attempts, nextAttemptAt, now())) {
              throw new Stale();
            }
            return replay(tx.connection(), payment);
          });
    }
    return change(
        payment,
        PaymentState.UNKNOWN,
        "payment.unknown",
        new Changes(null, null, null, null, attempts, nextAttemptAt, null),
        detail);
  }

  public Optional<PaymentRow> settle(PaymentRow payment, LocalDate valueDate) {
    String requestId = UUID.randomUUID().toString();
    return execute(
        payment,
        tx -> {
          Instant now = now();
          OptionalLong key = claim(tx.connection(), payment, "settle", now);
          if (key.isEmpty()) {
            return replay(tx.connection(), payment);
          }
          UUID clearing = internal(tx.connection(), "CLEARING", payment.currency());
          UUID settlement = internal(tx.connection(), "SETTLEMENT", payment.currency());
          PostedTransaction posted =
              post(
                  tx,
                  payment,
                  key.getAsLong(),
                  TransactionKind.EXTERNAL_SETTLEMENT,
                  Posting.move(payment.currency(), clearing, settlement, payment.amount()),
                  "payment.settle",
                  requestId);
          return transition(
              tx.connection(),
              payment,
              PaymentState.SETTLED,
              new Changes(null, null, posted.id(), valueDate, null, null, null),
              null,
              now,
              posted.auditId());
        });
  }

  public Optional<PaymentRow> compensate(PaymentRow payment, String reason) {
    String requestId = UUID.randomUUID().toString();
    return execute(
        payment,
        tx -> {
          Instant now = now();
          OptionalLong key = claim(tx.connection(), payment, "compensate", now);
          if (key.isEmpty()) {
            return replay(tx.connection(), payment);
          }
          UUID clearing = internal(tx.connection(), "CLEARING", payment.currency());
          Posting posting =
              Posting.of(
                  payment.currency(),
                  List.of(
                      new EntryLine(payment.sourceAccountId(), Side.CREDIT, payment.amount()),
                      new EntryLine(clearing, Side.DEBIT, payment.amount())));
          PostedTransaction posted =
              post(
                  tx,
                  payment,
                  key.getAsLong(),
                  TransactionKind.EXTERNAL_COMPENSATION,
                  posting,
                  "payment.compensate",
                  requestId);
          return transition(
              tx.connection(),
              payment,
              PaymentState.REJECTED_COMPENSATED,
              new Changes(null, null, posted.id(), null, null, null, reason),
              reason,
              now,
              posted.auditId());
        });
  }

  public Optional<PaymentRow> manualReview(PaymentRow payment, String reason) {
    Integer attempts = "unresolved after 8 status queries".equals(reason) ? 8 : null;
    return change(
        payment,
        PaymentState.MANUAL_REVIEW,
        "payment.manual_review",
        new Changes(null, null, null, null, attempts, null, reason),
        reason);
  }

  public Optional<PaymentRow> fail(PaymentRow payment, String reason) {
    String requestId = UUID.randomUUID().toString();
    return execute(payment, tx -> failIn(tx.connection(), payment, reason, now(), requestId));
  }

  private Optional<PaymentRow> change(
      PaymentRow payment, PaymentState to, String action, Changes changes, String detail) {
    String requestId = UUID.randomUUID().toString();
    return execute(
        payment,
        tx ->
            transition(
                tx.connection(),
                payment,
                to,
                changes,
                detail,
                now(),
                audit.record(tx.connection(), entry(payment, action, requestId))));
  }

  private StepResult failIn(
      Connection connection, PaymentRow payment, String reason, Instant now, String requestId)
      throws SQLException {
    return transition(
        connection,
        payment,
        PaymentState.FAILED,
        new Changes(null, null, null, null, null, null, reason),
        reason,
        now,
        audit.record(connection, entry(payment, "payment.fail", requestId)));
  }

  private PostedTransaction post(
      LedgerTx tx,
      PaymentRow payment,
      long keyId,
      TransactionKind kind,
      Posting posting,
      String action,
      String requestId)
      throws SQLException {
    tx.accounts(posting.accountIds());
    return writer.post(
        tx,
        new PostingCommand(
            kind,
            posting,
            keyId,
            null,
            null,
            Map.of(),
            entry(payment, action, requestId),
            CALLER,
            requestId));
  }

  private StepResult transition(
      Connection connection,
      PaymentRow payment,
      PaymentState to,
      Changes changes,
      String detail,
      Instant now,
      UUID auditId)
      throws SQLException {
    if (!payments.transition(connection, payment.id(), payment.state(), to, changes, now)) {
      throw new Stale();
    }
    payments.insertEvent(connection, payment.id(), payment.state(), to, detail, now, auditId);
    return new StepResult(payments.find(connection, payment.id()).orElseThrow(), true);
  }

  private OptionalLong claim(Connection connection, PaymentRow payment, String step, Instant now)
      throws SQLException {
    String key = "pay:" + payment.id() + ":" + step;
    Savepoint claim = connection.setSavepoint();
    OptionalLong id = keys.claim(connection, CALLER.clientId(), key, sha256(key), now);
    if (id.isEmpty()) {
      connection.rollback(claim);
    }
    return id;
  }

  private StepResult replay(Connection connection, PaymentRow payment) throws SQLException {
    return new StepResult(payments.find(connection, payment.id()).orElseThrow(), false);
  }

  private Optional<PaymentRow> execute(PaymentRow payment, TxWork<StepResult> work) {
    try {
      StepResult result = runner.execute(work).value();
      if (result.changed()) {
        log.info("payment {} {} -> {}", payment.id(), payment.state(), result.row().state());
        if (payment.state() == PaymentState.INITIATED
            && result.row().state() == PaymentState.HELD) {
          Failpoints.hit("payment.after_hold");
        } else if (payment.state() == PaymentState.HELD
            && result.row().state() == PaymentState.CAPTURED) {
          Failpoints.hit("payment.after_capture");
        }
      }
      return Optional.of(result.row());
    } catch (Stale exception) {
      return Optional.empty();
    }
  }

  private static AuditEntry entry(PaymentRow payment, String action, String requestId) {
    return AuditEntry.success(
        CALLER, action, "external_payment", payment.id().toString(), requestId);
  }

  private static Changes unchanged() {
    return new Changes(null, null, null, null, null, null, null);
  }

  private static UUID internal(Connection connection, String kind, String currency)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM ledger.account WHERE kind = ? AND currency = ?")) {
      select.setString(1, kind);
      select.setString(2, currency);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          throw new IllegalStateException("internal account missing");
        }
        return row.getObject("id", UUID.class);
      }
    }
  }

  private static byte[] sha256(String key) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private record StepResult(PaymentRow row, boolean changed) {}

  private static class Stale extends RuntimeException {}
}
