package tally.ledger.recon;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tally.ledger.config.JobConfig;
import tally.ledger.config.LedgerProperties;
import tally.ledger.posting.StrategyRunner;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.security.Caller;

@Component
@ConditionalOnExpression("'${tally.ledger.roles}'.contains('recon')")
public class ReconJob implements JobConfig.Job {

  private static final Logger log = LoggerFactory.getLogger(ReconJob.class);
  private final ReconStore store;
  private final ReconService service;
  private final StrategyRunner runner;
  private final LedgerProperties properties;
  private final Clock clock;

  public ReconJob(
      ReconStore store,
      ReconService service,
      StrategyRunner runner,
      LedgerProperties properties,
      Clock clock) {
    this.store = store;
    this.service = service;
    this.runner = runner;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public String name() {
    return "recon";
  }

  @Override
  public String role() {
    return "recon";
  }

  @Override
  public long pollMs() {
    return 60000;
  }

  @Override
  public int runOnce() {
    LocalDate date = LocalDate.now(clock).minusDays(1);
    LocalTime time = LocalTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (time.isBefore(properties.recon().timeUtc())
        || runner.execute(tx -> store.latest(tx.connection(), date).isPresent()).value()) {
      return 0;
    }
    try {
      service.run(Caller.job("recon"), date, null, UUID.randomUUID().toString());
      return 1;
    } catch (ApiException exception) {
      if (exception.code() != ErrorCode.STATEMENT_UNAVAILABLE) {
        throw exception;
      }
      log.warn("reconciliation deferred: {}", exception.getMessage());
      return 0;
    }
  }
}
