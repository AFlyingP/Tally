package tally.ledger.idempotency;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tally.ledger.config.LedgerProperties;
import tally.ledger.core.LedgerRuleException;
import tally.ledger.core.RuleCode;
import tally.ledger.posting.LedgerTx;
import tally.ledger.posting.RetryableConflict;
import tally.ledger.posting.StrategyRunner;
import tally.platform.error.ApiError;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.security.Caller;
import tally.platform.web.RequestIdFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
public class IdempotentExecutor {

  private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

  private final StrategyRunner runner;
  private final IdempotencyStore store;
  private final JsonMapper mapper;
  private final Clock clock;
  private final long waitMs;
  private final long lockWaitMs;

  public IdempotentExecutor(
      StrategyRunner runner,
      IdempotencyStore store,
      JsonMapper mapper,
      Clock clock,
      LedgerProperties properties) {
    this.runner = runner;
    this.store = store;
    this.mapper = mapper;
    this.clock = clock;
    this.waitMs = properties.idempotency().waitMs();
    this.lockWaitMs = properties.lockWaitMs();
  }

  @FunctionalInterface
  public interface Work {
    Outcome apply(LedgerTx tx, long keyId) throws SQLException;
  }

  public record Result(int status, String body, boolean replay, int attempts) {}

  private record Response(int status, String body, boolean replay) {}

  /** Claims a key and stores the response in the same transaction as the work. */
  public Result execute(Caller caller, String key, byte[] requestHash, Work work) {
    if (key == null || key.isBlank()) {
      throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "idempotency key is required");
    }
    if (!VALID_KEY.matcher(key).matches()) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED,
          "request is not valid",
          List.of(new ApiError.Detail("Idempotency-Key", "has the wrong format")));
    }
    StrategyRunner.Attempted<Response> attempted =
        runner.execute(tx -> execute(tx, caller, key, requestHash, work));
    Response response = attempted.value();
    return new Result(response.status(), response.body(), response.replay(), attempted.attempts());
  }

  private Response execute(LedgerTx tx, Caller caller, String key, byte[] requestHash, Work work)
      throws SQLException {
    Connection connection = tx.connection();
    setLockTimeout(connection, waitMs);
    OptionalLong claimed;
    try {
      claimed = store.claim(connection, caller.clientId(), key, requestHash, clock.instant());
    } catch (SQLException e) {
      if ("55P03".equals(e.getSQLState())) {
        throw new ApiException(ErrorCode.REQUEST_IN_FLIGHT, "request is still in flight")
            .retryAfter(1);
      }
      if ("40001".equals(e.getSQLState())) {
        RetryableConflict conflict = new RetryableConflict(e.getSQLState());
        conflict.initCause(e);
        throw conflict;
      }
      throw e;
    }
    if (claimed.isEmpty()) {
      IdempotencyStore.Stored stored =
          store
              .find(connection, caller.clientId(), key)
              .orElseThrow(() -> new RetryableConflict("idempotency key disappeared"));
      if (!Arrays.equals(requestHash, stored.requestHash())) {
        ErrorCode code = ErrorCode.IDEMPOTENCY_KEY_REUSED;
        return new Response(
            code.status(),
            mapper.writeValueAsString(error(code, "idempotency key was used for another request")),
            false);
      }
      return new Response(stored.statusCode(), stored.responseBody(), true);
    }

    long keyId = claimed.getAsLong();
    setLockTimeout(connection, lockWaitMs);
    Savepoint savepoint = connection.setSavepoint("work");
    Outcome outcome;
    try {
      outcome = work.apply(tx, keyId);
    } catch (LedgerRuleException e) {
      connection.rollback(savepoint);
      ErrorCode code = errorCode(e.code());
      outcome = new Outcome(code.status(), error(code, e.getMessage()));
    }
    String json = mapper.writeValueAsString(outcome.body());
    store.complete(connection, keyId, outcome.status(), json);
    return new Response(outcome.status(), json, false);
  }

  private static void setLockTimeout(Connection connection, long waitMs) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET LOCAL lock_timeout = '" + waitMs + "ms'");
    }
  }

  private static ErrorCode errorCode(RuleCode code) {
    return switch (code) {
      case ACCOUNT_NOT_FOUND -> ErrorCode.NOT_FOUND;
      case UNBALANCED, INVALID_AMOUNT, INVALID_CURRENCY, INVALID_EXPIRY ->
          ErrorCode.VALIDATION_FAILED;
      default -> ErrorCode.valueOf(code.name());
    };
  }

  private static ApiError error(ErrorCode code, String message) {
    return new ApiError(
        new ApiError.Body(code.name(), message, RequestIdFilter.current(), List.of()));
  }
}
