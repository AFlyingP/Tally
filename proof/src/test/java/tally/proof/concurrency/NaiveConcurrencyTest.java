package tally.proof.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tally.proof.harness.InProcessLedger;
import tally.proof.harness.Seeds;
import tally.proof.naive.NaiveConfig;

@Tag("concurrency")
class NaiveConcurrencyTest {

  @Test
  void naiveStrategyIsDetected() {
    InProcessLedger ledger = InProcessLedger.start("naive", Clock.systemUTC(), NaiveConfig.class);
    try {
      Seeds.print("concurrency");
      ConcurrencyRun.Result result =
          ConcurrencyRun.run(ledger, Workload.HOT, 20_000, 32, Seeds.seed(), true);
      System.out.println(
          "strategy=naive workload="
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
              + String.format(Locale.ROOT, "%.1f", result.seconds())
              + " violations="
              + result.violations().size()
              + " double_posted_pairs="
              + result.doublePostedPairs()
              + " unexpected="
              + result.unexpected());
      assertThat(result.violations().size() + result.doublePostedPairs())
          .as("detected naive concurrency failures")
          .isGreaterThan(0);
    } finally {
      ledger.stop();
    }
  }
}
