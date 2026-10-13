package tally.ledger.holds;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Service;
import tally.ledger.api.CanonicalJson;
import tally.ledger.api.HoldDto;
import tally.ledger.api.TransactionDto;
import tally.ledger.audit.AuditEntry;
import tally.ledger.audit.AuditWriter;
import tally.ledger.core.AccountKind;
import tally.ledger.core.AccountState;
import tally.ledger.core.Amounts;
import tally.ledger.core.HoldRules;
import tally.ledger.core.HoldState;
import tally.ledger.core.LedgerRuleException;
import tally.ledger.core.Posting;
import tally.ledger.core.RequestHash;
import tally.ledger.core.RuleCode;
import tally.ledger.core.TransactionKind;
import tally.ledger.idempotency.IdempotentExecutor;
import tally.ledger.idempotency.IdempotentExecutor.Result;
import tally.ledger.idempotency.Outcome;
import tally.ledger.posting.LedgerTx;
import tally.ledger.posting.LedgerWriter;
import tally.ledger.posting.PostedTransaction;
import tally.ledger.posting.PostingCommand;
import tally.ledger.posting.RetryableConflict;
import tally.ledger.posting.StrategyRunner;
import tally.platform.error.ApiError;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.id.UuidV7;
import tally.platform.security.Caller;
import tally.platform.web.RequestIdFilter;

@Service
public class HoldService {

  private static final Logger log = LoggerFactory.getLogger(HoldService.class);
  private static final Set<String> CURRENCIES =
      Currency.getAvailableCurrencies().stream()
          .map(Currency::getCurrencyCode)
          .collect(Collectors.toUnmodifiableSet());

  private final DataSource dataSource;
  private final HoldStore holds;
  private final AuditWriter audit;
  private final Clock clock;
  private final IdempotentExecutor executor;
  private final LedgerWriter writer;
  private final StrategyRunner runner;

  public HoldService(
      DataSource dataSource,
      HoldStore holds,
      AuditWriter audit,
      Clock clock,
      IdempotentExecutor executor,
      LedgerWriter writer,
      StrategyRunner runner) {
    this.dataSource = dataSource;
    this.holds = holds;
    this.audit = audit;
    this.clock = clock;
    this.executor = executor;
    this.writer = writer;
    this.runner = runner;
  }

  public record Placed(UUID holdId, UUID auditId) {}

  public Result place(Caller caller, String key, HoldDto.Place request) {
    requireCurrency(request.currency());
    byte[] hash = RequestHash.of("POST", "/v1/holds", CanonicalJson.of(request));
    String requestId = RequestIdFilter.current();
    return executor.execute(
        caller,
        key,
        hash,
        (tx, keyId) -> {
          UUID holdId = UuidV7.next(clock);
          AuditEntry entry =
              AuditEntry.success(caller, "hold.place", "hold", holdId.toString(), requestId);
          Placed placed =
              placeIn(
                  tx,
                  keyId,
                  holdId,
                  request.accountId(),
                  request.currency(),
                  request.amount(),
                  request.expiresAt(),
                  entry,
                  caller);
          HoldStore.HoldRow row = holds.find(tx.connection(), placed.holdId()).orElseThrow();
          return new Outcome(201, toDto(row));
        });
  }

  public HoldDto.Hold get(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      return toDto(requireHold(connection, id));
    }
  }

  public Result capture(Caller caller, String key, UUID holdId, HoldDto.Capture request)
      throws SQLException {
    requireHoldOutside(holdId);
    String path = "/v1/holds/" + holdId + "/capture";
    byte[] hash = RequestHash.of("POST", path, CanonicalJson.of(request));
    String requestId = RequestIdFilter.current();
    return executor.execute(
        caller,
        key,
        hash,
        (tx, keyId) -> {
          PostedTransaction posted =
              captureIn(
                  tx,
                  keyId,
                  holdId,
                  request.amount(),
                  request.destinationAccountId(),
                  true,
                  AuditEntry.success(caller, "hold.capture", "hold", holdId.toString(), requestId),
                  caller,
                  requestId);
          return new Outcome(201, TransactionDto.Transaction.from(posted));
        });
  }

  public Result release(Caller caller, String key, UUID holdId) throws SQLException {
    String path = "/v1/holds/" + holdId + "/release";
    byte[] hash = RequestHash.of("POST", path, CanonicalJson.empty());
    String requestId = RequestIdFilter.current();
    return executor.execute(
        caller,
        key,
        hash,
        (tx, keyId) -> {
          HoldDto.Hold released =
              releaseIn(
                  tx,
                  holdId,
                  AuditEntry.success(caller, "hold.release", "hold", holdId.toString(), requestId),
                  caller);
          return new Outcome(200, released);
        });
  }

  public Placed placeIn(
      LedgerTx tx,
      long keyId,
      UUID accountId,
      String currency,
      long amount,
      Instant expiresAt,
      AuditEntry auditEntry,
      Caller caller)
      throws SQLException {
    return placeIn(
        tx, keyId, UuidV7.next(clock), accountId, currency, amount, expiresAt, auditEntry, caller);
  }

  public PostedTransaction captureIn(
      LedgerTx tx,
      long keyId,
      UUID holdId,
      long amount,
      UUID destinationAccountId,
      boolean fromApi,
      AuditEntry auditEntry,
      Caller caller,
      String requestId)
      throws SQLException {
    Connection connection = tx.connection();
    HoldStore.HoldRow first = requireHold(connection, holdId);
    Map<UUID, AccountState> accounts =
        tx.accounts(new TreeSet<>(List.of(first.accountId(), destinationAccountId)));
    if (!accounts.containsKey(first.accountId()) || !accounts.containsKey(destinationAccountId)) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_NOT_FOUND, "account not found");
    }
    HoldStore.HoldRow hold = requireHold(connection, holdId);
    HoldRules.requireCapturable(
        hold.state(), hold.amount(), amount, hold.expiresAt(), clock.instant());
    if (hold.accountId().equals(destinationAccountId)) {
      throw new LedgerRuleException(RuleCode.SAME_ACCOUNT, "accounts must be different");
    }
    AccountState destination = accounts.get(destinationAccountId);
    if (fromApi && destination.kind() != AccountKind.CUSTOMER) {
      throw new LedgerRuleException(
          RuleCode.INTERNAL_ACCOUNT, "destination must be a customer account");
    }
    Posting posting = Posting.move(hold.currency(), hold.accountId(), destinationAccountId, amount);
    PostedTransaction posted =
        writer.post(
            tx,
            new PostingCommand(
                TransactionKind.HOLD_CAPTURE,
                posting,
                keyId,
                null,
                null,
                Map.of(hold.accountId(), -hold.amount()),
                auditEntry,
                caller,
                requestId));
    Instant now = now();
    if (holds.markCaptured(connection, holdId, amount, posted.id(), now) == 0) {
      throw new RetryableConflict("hold");
    }
    holds.insertEvent(
        connection,
        holdId,
        "CAPTURED",
        amount,
        posted.id(),
        caller.clientId(),
        now,
        posted.auditId());
    return posted;
  }

  public int expireDue(int limit) {
    List<UUID> due;
    Instant now = now();
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      due = holds.due(connection, now, limit);
    } catch (SQLException e) {
      throw new UncategorizedSQLException("hold expiry", "SELECT due holds", e);
    }
    int expired = 0;
    Caller caller = Caller.job("expiry");
    for (UUID holdId : due) {
      String requestId = RequestIdFilter.current();
      try {
        if (runner.execute(tx -> expireIn(tx, holdId, caller, requestId)).value()) {
          expired++;
        }
      } catch (ApiException e) {
        if (e.code() != ErrorCode.RETRY_EXHAUSTED) {
          throw e;
        }
        log.warn("hold expiry retries exhausted: hold={}", holdId);
      }
    }
    return expired;
  }

  private Placed placeIn(
      LedgerTx tx,
      long keyId,
      UUID holdId,
      UUID accountId,
      String currency,
      long amount,
      Instant expiresAt,
      AuditEntry auditEntry,
      Caller caller)
      throws SQLException {
    Map<UUID, AccountState> accounts = tx.accounts(new TreeSet<>(Set.of(accountId)));
    AccountState account = accounts.get(accountId);
    if (account == null) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_NOT_FOUND, "account not found");
    }
    Instant now = now();
    HoldRules.requirePlaceable(account, amount, expiresAt, now);
    if (!currency.equals(account.currency())) {
      throw new LedgerRuleException(RuleCode.CURRENCY_MISMATCH, "account currency does not match");
    }
    tx.write(account, account.balance(), Amounts.add(account.held(), amount));
    UUID auditId = audit.record(tx.connection(), auditEntry);
    HoldStore.HoldRow hold =
        new HoldStore.HoldRow(
            holdId,
            accountId,
            currency,
            amount,
            HoldState.ACTIVE,
            expiresAt,
            null,
            null,
            keyId,
            caller.clientId(),
            now);
    holds.insert(tx.connection(), hold);
    holds.insertEvent(
        tx.connection(), holdId, "PLACED", amount, null, caller.clientId(), now, auditId);
    return new Placed(holdId, auditId);
  }

  private HoldDto.Hold releaseIn(LedgerTx tx, UUID holdId, AuditEntry auditEntry, Caller caller)
      throws SQLException {
    Connection connection = tx.connection();
    HoldStore.HoldRow first = requireHold(connection, holdId);
    Map<UUID, AccountState> accounts = tx.accounts(new TreeSet<>(Set.of(first.accountId())));
    HoldStore.HoldRow hold = requireHold(connection, holdId);
    HoldRules.requireReleasable(hold.state());
    AccountState account = accounts.get(hold.accountId());
    if (account == null) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_NOT_FOUND, "account not found");
    }
    tx.write(account, account.balance(), Amounts.subtract(account.held(), hold.amount()));
    Instant now = now();
    UUID auditId = audit.record(connection, auditEntry);
    if (holds.markReleased(connection, holdId, now) == 0) {
      throw new RetryableConflict("hold");
    }
    holds.insertEvent(
        connection, holdId, "RELEASED", hold.amount(), null, caller.clientId(), now, auditId);
    return toDto(
        new HoldStore.HoldRow(
            hold.id(),
            hold.accountId(),
            hold.currency(),
            hold.amount(),
            HoldState.RELEASED,
            hold.expiresAt(),
            hold.capturedAmount(),
            hold.captureTransactionId(),
            hold.keyId(),
            hold.clientId(),
            hold.createdAt()));
  }

  private boolean expireIn(LedgerTx tx, UUID holdId, Caller caller, String requestId)
      throws SQLException {
    Connection connection = tx.connection();
    HoldStore.HoldRow first = holds.find(connection, holdId).orElse(null);
    if (first == null) {
      return false;
    }
    Map<UUID, AccountState> accounts = tx.accounts(new TreeSet<>(Set.of(first.accountId())));
    HoldStore.HoldRow hold = holds.find(connection, holdId).orElse(null);
    if (hold == null || hold.state() != HoldState.ACTIVE) {
      return false;
    }
    AccountState account = accounts.get(hold.accountId());
    if (account == null) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_NOT_FOUND, "account not found");
    }
    Instant now = now();
    tx.write(account, account.balance(), Amounts.subtract(account.held(), hold.amount()));
    UUID auditId =
        audit.record(
            connection,
            AuditEntry.success(caller, "hold.expire", "hold", holdId.toString(), requestId));
    if (holds.markExpired(connection, holdId, now) == 0) {
      throw new RetryableConflict("hold");
    }
    holds.insertEvent(
        connection, holdId, "EXPIRED", hold.amount(), null, caller.clientId(), now, auditId);
    return true;
  }

  private void requireHoldOutside(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      requireHold(connection, id);
    }
  }

  private HoldStore.HoldRow requireHold(Connection connection, UUID id) throws SQLException {
    return holds
        .find(connection, id)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "hold not found"));
  }

  private static HoldDto.Hold toDto(HoldStore.HoldRow row) {
    return new HoldDto.Hold(
        row.id(),
        row.accountId(),
        row.currency(),
        row.amount(),
        row.state(),
        row.expiresAt(),
        row.capturedAmount(),
        row.captureTransactionId(),
        row.createdAt());
  }

  private static void requireCurrency(String currency) {
    if (currency == null || !CURRENCIES.contains(currency)) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED,
          "request is not valid",
          List.of(new ApiError.Detail("currency", "must be a known 3-letter currency code")));
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
