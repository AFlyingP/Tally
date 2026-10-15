package tally.ledger.schedule;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tally.ledger.audit.AuditEntry;
import tally.ledger.audit.AuditWriter;
import tally.ledger.config.JobConfig.Job;
import tally.ledger.config.LedgerProperties;
import tally.ledger.core.LedgerRuleException;
import tally.ledger.core.Posting;
import tally.ledger.core.Recurrence;
import tally.ledger.core.RuleCode;
import tally.ledger.core.TransactionKind;
import tally.ledger.failpoint.Failpoints;
import tally.ledger.idempotency.IdempotencyStore;
import tally.ledger.posting.LedgerTx;
import tally.ledger.posting.LedgerWriter;
import tally.ledger.posting.PostingCommand;
import tally.ledger.posting.StrategyRunner;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.id.UuidV7;
import tally.platform.security.Caller;

@Component
public final class ScheduleRunner implements Job {

  private enum PostResult {
    ALREADY_POSTED,
    RETRY,
    TERMINAL
  }

  private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);
  private static final int MAX_PER_RUN = 20;
  private static final String CLIENT_ID = "job:scheduler";

  private final ScheduleStore schedules;
  private final IdempotencyStore idempotency;
  private final AuditWriter audit;
  private final LedgerWriter writer;
  private final StrategyRunner runner;
  private final LedgerProperties properties;
  private final Clock clock;

  public ScheduleRunner(
      ScheduleStore schedules,
      IdempotencyStore idempotency,
      AuditWriter audit,
      LedgerWriter writer,
      StrategyRunner runner,
      LedgerProperties properties,
      Clock clock) {
    this.schedules = schedules;
    this.idempotency = idempotency;
    this.audit = audit;
    this.writer = writer;
    this.runner = runner;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public String name() {
    return "scheduler";
  }

  @Override
  public String role() {
    return "scheduler";
  }

  @Override
  public long pollMs() {
    return properties.scheduler().pollMs();
  }

  @Override
  public int runOnce() {
    int handled = 0;
    while (handled < MAX_PER_RUN) {
      try {
        if (!executeOne()) {
          break;
        }
        handled++;
      } catch (ApiException e) {
        if (e.code() == ErrorCode.RETRY_EXHAUSTED) {
          log.warn("occurrence deferred: retries exhausted");
        } else {
          log.error("schedule runner failed", e);
        }
        break;
      } catch (RuntimeException e) {
        log.error("schedule runner failed", e);
        break;
      }
    }
    return handled;
  }

  private boolean executeOne() {
    return runner
        .execute(
            tx -> {
              Instant now = now();
              Optional<ScheduleStore.DueOccurrence> found =
                  schedules.claimDue(tx.connection(), now);
              if (found.isEmpty()) {
                return false;
              }
              ScheduleStore.DueOccurrence occurrence = found.orElseThrow();
              Caller caller = Caller.job("scheduler");
              String requestId = UUID.randomUUID().toString();
              if (occurrence.dueAt().isBefore(now.minus(7, ChronoUnit.DAYS))) {
                schedules.markTerminal(
                    tx.connection(),
                    occurrence.id(),
                    "SKIPPED",
                    "MISSED_WINDOW",
                    occurrence.attempts(),
                    now);
                record(
                    tx.connection(),
                    caller,
                    "occurrence.skip",
                    occurrence.id(),
                    requestId,
                    "MISSED_WINDOW");
                advance(tx.connection(), occurrence, now);
                return true;
              }
              PostResult result = post(tx, occurrence, caller, requestId, now);
              if (result == PostResult.ALREADY_POSTED) {
                advance(tx.connection(), occurrence, now);
              } else if (result == PostResult.TERMINAL) {
                Failpoints.hit("scheduler.before_commit");
                advance(tx.connection(), occurrence, now);
              }
              return true;
            })
        .value();
  }

  private PostResult post(
      LedgerTx tx,
      ScheduleStore.DueOccurrence occurrence,
      Caller caller,
      String requestId,
      Instant now)
      throws SQLException {
    Connection connection = tx.connection();
    String key = "sched:" + occurrence.scheduleId() + ":" + occurrence.occurrenceDate();
    Savepoint attempt = connection.setSavepoint("attempt");
    OptionalLong claimed = idempotency.claim(connection, CLIENT_ID, key, keyHash(key), now);
    if (claimed.isEmpty()) {
      IdempotencyStore.Stored existing = idempotency.find(connection, CLIENT_ID, key).orElseThrow();
      UUID transactionId = transactionForKey(connection, existing.id());
      schedules.markPosted(connection, occurrence.id(), transactionId, now);
      return PostResult.ALREADY_POSTED;
    }
    try {
      var posted =
          writer.post(
              tx,
              new PostingCommand(
                  TransactionKind.TRANSFER,
                  Posting.move(
                      occurrence.currency(),
                      occurrence.sourceAccountId(),
                      occurrence.destinationAccountId(),
                      occurrence.amount()),
                  claimed.getAsLong(),
                  null,
                  occurrence.description(),
                  Map.of(),
                  AuditEntry.success(
                      caller,
                      "occurrence.post",
                      "schedule_occurrence",
                      occurrence.id().toString(),
                      requestId),
                  caller,
                  requestId));
      schedules.markPosted(connection, occurrence.id(), posted.id(), now);
      return PostResult.TERMINAL;
    } catch (LedgerRuleException e) {
      connection.rollback(attempt);
      RuleCode code = e.code();
      int attempts = occurrence.attempts();
      if (code == RuleCode.INSUFFICIENT_FUNDS) {
        attempts++;
        if (attempts <= 3) {
          schedules.markRetry(
              connection,
              occurrence.id(),
              attempts,
              now.plus(properties.scheduler().retryIntervalMs(), ChronoUnit.MILLIS),
              now);
          record(connection, caller, "occurrence.retry", occurrence.id(), requestId, code.name());
          return PostResult.RETRY;
        }
      }
      schedules.markTerminal(connection, occurrence.id(), "FAILED", code.name(), attempts, now);
      record(connection, caller, "occurrence.fail", occurrence.id(), requestId, code.name());
      return PostResult.TERMINAL;
    }
  }

  private void advance(Connection connection, ScheduleStore.DueOccurrence occurrence, Instant now)
      throws SQLException {
    Optional<LocalDate> next =
        Recurrence.next(
            occurrence.frequency(),
            occurrence.occurrenceDate(),
            occurrence.dayOfMonth(),
            occurrence.endDate());
    if (next.isPresent()) {
      LocalDate date = next.orElseThrow();
      schedules.insertOccurrence(
          connection,
          UuidV7.next(clock),
          occurrence.scheduleId(),
          date,
          Recurrence.dueAt(date, occurrence.startAt()),
          now);
    } else {
      schedules.complete(connection, occurrence.scheduleId(), now);
    }
  }

  private void record(
      Connection connection,
      Caller caller,
      String action,
      UUID occurrenceId,
      String requestId,
      String reason)
      throws SQLException {
    AuditEntry entry =
        AuditEntry.success(
            caller, action, "schedule_occurrence", occurrenceId.toString(), requestId);
    audit.record(
        connection,
        new AuditEntry(
            entry.actor(),
            entry.actorRole(),
            entry.scopes(),
            entry.action(),
            entry.resourceType(),
            entry.resourceId(),
            entry.requestId(),
            entry.outcome(),
            reason,
            entry.transactionId()));
  }

  private static UUID transactionForKey(Connection connection, long keyId) throws SQLException {
    try (var select =
        connection.prepareStatement(
            "SELECT id FROM ledger.ledger_transaction WHERE idempotency_key_id = ?")) {
      select.setLong(1, keyId);
      try (var row = select.executeQuery()) {
        if (!row.next()) {
          throw new SQLException("idempotency key has no transaction");
        }
        return row.getObject("id", UUID.class);
      }
    }
  }

  private static byte[] keyHash(String key) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
