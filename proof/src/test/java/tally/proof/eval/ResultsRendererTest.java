package tally.proof.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ResultsRendererTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void comparisonTableHasOneRowPerMeasurement() {
    String table = ResultsRenderer.comparisonTable(results());

    assertThat(table.lines()).hasSize(6);
    assertThat(table)
        .contains(
            "| Strategy | Workload | Clients | Transfers/s | p50 ms | p99 ms | Retries | Exhausted |")
        .contains("| pessimistic | hot | 4 | 12.3 | 2.3 | 8.8 | 6 | 1 |")
        .contains("| optimistic | uniform | 8 | 10.0 | 1.5 | 7.1 | 4 | 0 |")
        .contains(
            "Median of 3 runs of 30 seconds each, on Test CPU, 8 cores, 16000 MB, Docker limit 12000 MB.");
  }

  @Test
  void readmeBlockListsEveryMeasureInOrder() {
    assertThat(
            ResultsRenderer.readmeBlock(results())
                .lines()
                .filter(
                    line ->
                        line.startsWith("| ")
                            && !line.startsWith("| Measure")
                            && !line.startsWith("| ---"))
                .map(line -> line.substring(2, line.indexOf(" |", 2)))
                .toList())
        .containsExactly(
            "Concurrent operations per strategy",
            "Invariant violations after those runs",
            "Fault scenarios run",
            "Duplicate postings in fault runs",
            "Duplicate scheduled payments (scenario 6)",
            "External payments",
            "Money created or lost in those payments",
            "Events missing after relay and broker faults",
            "Reconciliation breaks found with the right type",
            "Monitoring precision / recall, R1",
            "Monitoring precision / recall, R2",
            "Monitoring precision / recall, R3",
            "Alert difference with every event delivered twice",
            "Audit rows per state change",
            "Tampered audit rows detected",
            "Recovery after API kill, median",
            "Recovery after database restart, median",
            "Property test sequences per strategy",
            "Mutation score, ledger core",
            "Tests",
            "Naive baseline: violations found");
  }

  @Test
  void readmeBlockCopiesValuesExactly() {
    String block = ResultsRenderer.readmeBlock(results());

    assertThat(block)
        .contains("| Concurrent operations per strategy | 1000000 |")
        .contains(
            "| External payments | 2000 (settled 66.7%, compensated 20.0%, manual review 10.0%, failed 3.3%) |")
        .contains("| Mutation score, ledger core | 81.2% (81 of 100 mutants) |");
  }

  @Test
  void replaceBlockKeepsTextOutsideMarkers() {
    assertThat(
            ResultsRenderer.replaceBlock(
                "before\n<!-- start -->\nold\n<!-- end -->\nafter",
                "<!-- start -->",
                "<!-- end -->",
                "new"))
        .isEqualTo("before\n<!-- start -->\n\nnew\n\n<!-- end -->\nafter");
  }

  @Test
  void replaceBlockFailsWithoutMarkers() {
    assertThatThrownBy(
            () -> ResultsRenderer.replaceBlock("body", "<!-- start -->", "<!-- end -->", "new"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("marker not found");
  }

  @Test
  void checkModeDetectsStaleBlock() {
    String document = "<!-- start -->\nright!\n<!-- end -->";
    String rendered =
        ResultsRenderer.replaceBlock(document, "<!-- start -->", "<!-- end -->", "right");

    assertThat(document).isNotEqualTo(rendered);
  }

  @Test
  void renderingTwiceIsStable() {
    String once =
        ResultsRenderer.replaceBlock(
            "top\n<!-- start -->\nold\n<!-- end -->\nbottom",
            "<!-- start -->",
            "<!-- end -->",
            "content");

    assertThat(ResultsRenderer.replaceBlock(once, "<!-- start -->", "<!-- end -->", "content"))
        .isEqualTo(once);
  }

  private static JsonNode results() {
    return JSON.readTree(
        """
        {
          "generated_at":"2026-10-17T10:00:00Z","git_commit":"1234567890abcdef",
          "machine":{"cpu_model":"Test CPU","cpu_cores":8,"memory_mb":16000,"docker_memory_mb":12000},
          "comparison":{"rows":[
            {"strategy":"pessimistic","workload":"hot","clients":4,"runs":3,"transfers_per_s":{"median":12.34},"p50_ms":{"median":2.34},"p99_ms":{"median":8.76},"retries":{"median":5.5},"exhausted":{"median":1}},
            {"strategy":"optimistic","workload":"uniform","clients":8,"runs":3,"transfers_per_s":{"median":10},"p50_ms":{"median":1.5},"p99_ms":{"median":7.12},"retries":{"median":4},"exhausted":{"median":0}}
          ]},
          "concurrency":{"pessimistic":{"operations":1000000,"violations":0},"optimistic":{"operations":1000000,"violations":0},"serializable":{"operations":1000000,"violations":0}},
          "faults":{"pessimistic":{"runs":220,"duplicate_postings":0,"duplicate_scheduled":0},"optimistic":{"runs":220,"duplicate_postings":1,"duplicate_scheduled":0},"serializable":{"runs":220,"duplicate_postings":0,"duplicate_scheduled":2}},
          "payments":{"count":2000,"created_or_lost":0,"shares":{"SETTLED":0.6665,"REJECTED_COMPENSATED":0.2,"MANUAL_REVIEW":0.1,"FAILED":0.0333}},
          "events":{"missing":0,"duplicates":0},"recon":{"correct":300,"seeded":300,"clean_files":20,"false_breaks":0},
          "monitoring":{"rules":[{"rule":"R1","precision":0.9876,"recall":0.9999},{"rule":"R2","precision":0.9,"recall":0.8},{"rule":"R3","precision":0.7,"recall":0.6}],"double_delivery_diff":0},
          "audit":{"rows_per_change_min":1,"rows_per_change_max":1,"tamper_detected":20,"tamper_cases":20},
          "recovery":{"api_kill_ms":{"median":4765.2846},"db_restart_ms":{"median":4544.0645}},
          "property":{"pessimistic":{"tries":500,"sequence_length":100}},"mutation":{"score":0.8123,"killed":81,"mutants":100},
          "tests":{"count":526},"baseline":{"naive":{"violations":649,"double_posted_pairs":972}}
        }
        """);
  }
}
