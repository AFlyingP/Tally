package tally.proof.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tally.proof.harness.Seeds;
import tally.proof.harness.Sizes;

@Tag("load")
class LoadSmokeTest {

  private static final List<String> ROW_PROPERTIES =
      List.of(
          "strategy",
          "workload",
          "clients",
          "runs",
          "transfers_per_s",
          "p50_ms",
          "p99_ms",
          "retries",
          "exhausted",
          "insufficient");

  @Test
  void loadScriptRunsForEachStrategy() {
    Seeds.print("load");
    List<Map<String, Object>> rows = LoadComparison.run(Sizes.current(), Seeds.seed());

    assertThat(rows).hasSize(3);
    for (Map<String, Object> row : rows) {
      assertThat(row.keySet()).containsExactlyElementsOf(ROW_PROPERTIES);
      Map<?, ?> transfersPerSecond = (Map<?, ?>) row.get("transfers_per_s");
      assertThat(((Number) transfersPerSecond.get("median")).doubleValue()).isGreaterThan(0);
      System.out.println(
          "strategy="
              + row.get("strategy")
              + " workload="
              + row.get("workload")
              + " clients="
              + row.get("clients")
              + " transfers_per_s="
              + median(row, "transfers_per_s")
              + " p50_ms="
              + median(row, "p50_ms")
              + " p99_ms="
              + median(row, "p99_ms")
              + " retries="
              + median(row, "retries")
              + " exhausted="
              + median(row, "exhausted"));
    }
  }

  private static String median(Map<String, Object> row, String name) {
    Map<?, ?> stats = (Map<?, ?>) row.get(name);
    return String.format(Locale.ROOT, "%.4f", ((Number) stats.get("median")).doubleValue());
  }
}
