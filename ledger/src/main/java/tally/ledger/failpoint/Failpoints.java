package tally.ledger.failpoint;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tally.ledger.config.LedgerProperties;

@Component
public final class Failpoints {

  private static final Logger log = LoggerFactory.getLogger(Failpoints.class);
  private static final Set<String> NAMES =
      Set.of(
          "scheduler.before_commit",
          "payment.after_hold",
          "payment.after_capture",
          "payment.after_submit",
          "relay.after_send");
  private static volatile String configured = "";

  public Failpoints(LedgerProperties properties) {
    configure(properties.failpoint());
  }

  public static void configure(String name) {
    if (name == null || (!name.isEmpty() && !NAMES.contains(name))) {
      throw new IllegalArgumentException("unknown failpoint: " + name);
    }
    configured = name;
  }

  public static void hit(String name) {
    if (configured.equals(name)) {
      log.error("failpoint hit: {}", name);
      Runtime.getRuntime().halt(137);
    }
  }
}
