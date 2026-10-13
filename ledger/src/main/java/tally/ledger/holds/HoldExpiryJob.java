package tally.ledger.holds;

import org.springframework.stereotype.Component;
import tally.ledger.config.JobConfig.Job;
import tally.ledger.config.LedgerProperties;

@Component
public final class HoldExpiryJob implements Job {

  private final HoldService holdService;
  private final LedgerProperties properties;

  public HoldExpiryJob(HoldService holdService, LedgerProperties properties) {
    this.holdService = holdService;
    this.properties = properties;
  }

  @Override
  public String name() {
    return "expiry";
  }

  @Override
  public String role() {
    return "expiry";
  }

  @Override
  public long pollMs() {
    return properties.expiry().pollMs();
  }

  @Override
  public int runOnce() {
    return holdService.expireDue(100);
  }
}
