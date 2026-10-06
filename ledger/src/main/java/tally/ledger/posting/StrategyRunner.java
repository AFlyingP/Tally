package tally.ledger.posting;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.SplittableRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Component;
import tally.ledger.config.LedgerProperties;
import tally.ledger.core.Backoff;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;

@Component
public class StrategyRunner {

  private static final Logger log = LoggerFactory.getLogger(StrategyRunner.class);

  private final PostingStrategy strategy;
  private final LedgerProperties.Retry retry;
  private final SplittableRandom random = new SplittableRandom();

  public StrategyRunner(PostingStrategy strategy, LedgerProperties properties) {
    if (!strategy.name().equals(properties.strategy())) {
      throw new IllegalArgumentException(
          "invalid configuration: tally.ledger.strategy: unknown strategy "
              + properties.strategy());
    }
    this.strategy = strategy;
    this.retry = properties.retry();
  }

  public record Attempted<T>(T value, int attempts) {}

  public <T> Attempted<T> execute(TxWork<T> work) {
    for (int attempt = 1; ; attempt++) {
      try {
        return new Attempted<>(strategy.run(work), attempt);
      } catch (RetryableConflict e) {
        if (attempt == retry.maxAttempts()) {
          log.warn("retry exhausted after {} attempts", attempt);
          throw new ApiException(ErrorCode.RETRY_EXHAUSTED, "retries exhausted").retryAfter(1);
        }
        log.debug("retry attempt={} cause={}", attempt, e.getMessage());
        long delay;
        synchronized (random) {
          delay = Backoff.delayMs(attempt, retry.baseMs(), retry.capMs(), random);
        }
        try {
          Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("retry interrupted", interrupted);
        }
      } catch (SQLTransientConnectionException e) {
        throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "database is unavailable")
            .retryAfter(1);
      } catch (SQLException e) {
        throw new UncategorizedSQLException("posting", null, e);
      }
    }
  }
}
