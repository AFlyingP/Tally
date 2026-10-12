package tally.proof.concurrency;

import java.time.Clock;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tally.proof.harness.InProcessLedger;
import tally.proof.harness.Seeds;
import tally.proof.harness.Sizes;

@Tag("concurrency")
class ConcurrencySuiteTest {

  @Test
  void pessimistic() {
    suite("pessimistic");
  }

  @Test
  void optimistic() {
    suite("optimistic");
  }

  @Test
  void serializable() {
    suite("serializable");
  }

  private void suite(String strategy) {
    String selectedStrategy = System.getProperty("proof.strategy");
    Assumptions.assumeTrue(selectedStrategy == null || selectedStrategy.equals(strategy));
    InProcessLedger ledger = InProcessLedger.start(strategy, Clock.systemUTC());
    try {
      Seeds.print("concurrency");
      Sizes sizes = Sizes.current();
      for (Workload workload : new Workload[] {Workload.UNIFORM, Workload.HOT}) {
        ConcurrencyRun.Result result =
            ConcurrencyRun.run(
                ledger,
                workload,
                sizes.concurrencyOperations() / 2,
                sizes.concurrencyThreads(),
                Seeds.seed());
        result.assertCorrect();
        System.out.println(
            "strategy="
                + strategy
                + " workload="
                + result.workload().name().toLowerCase(Locale.ROOT)
                + " operations="
                + result.operations()
                + " posted="
                + result.posted()
                + " insufficient="
                + result.insufficient()
                + " exhausted="
                + result.exhausted()
                + " seconds="
                + String.format(Locale.ROOT, "%.1f", result.seconds()));
      }
    } finally {
      ledger.stop();
    }
  }
}
