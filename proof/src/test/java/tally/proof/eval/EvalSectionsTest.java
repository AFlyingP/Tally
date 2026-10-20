package tally.proof.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tally.proof.fault.FaultResult;
import tally.proof.fault.Scenario;

class EvalSectionsTest {

  @Test
  void orchestratorKillCountsPaymentStatesInsteadOfCreatedOrLost() {
    EvalSections.RunCounts settled =
        EvalSections.countRun(result(Map.of("SETTLED", 5), null), Scenario.ORCHESTRATOR_KILL);
    assertThat(settled.violations()).isZero();

    EvalSections.RunCounts notSettled =
        EvalSections.countRun(
            result(Map.of("SETTLED", 4, "PROCESSING", 1), null), Scenario.ORCHESTRATOR_KILL);
    assertThat(notSettled.violations()).isEqualTo(1);
    assertThat(notSettled.reasons()).contains("payment_states={PROCESSING=1}");
  }

  @Test
  void extbankFaultsStillRequireCreatedOrLostMeasurement() {
    EvalSections.RunCounts counts =
        EvalSections.countRun(result(Map.of("SETTLED", 1), null), Scenario.EXTBANK_FAULTS);
    assertThat(counts.violations()).isEqualTo(1);
    assertThat(counts.reasons()).contains("created_or_lost=unmeasured");
  }

  @Test
  void mergeFaultsReplacesRerunScenariosAndAdjustsTotals() {
    Map<String, Object> existing =
        Map.of(
            "pessimistic",
            Map.of(
                "runs", 40,
                "duplicate_postings", 1,
                "duplicate_scheduled", 2,
                "violations", 20,
                "scenarios",
                    List.of(
                        Map.of(
                            "id",
                            6,
                            "runs",
                            20,
                            "violations",
                            0,
                            "duplicate_scheduled",
                            2,
                            "duplicate_postings",
                            1),
                        Map.of("id", 10, "runs", 20, "violations", 20, "duplicate_postings", 0))));
    Map<String, Object> rerun =
        Map.of(
            "pessimistic",
            Map.of(
                "scenarios",
                List.of(Map.of("id", 10, "runs", 20, "violations", 0, "duplicate_postings", 0))));

    Map<String, Object> merged = EvalSections.mergeFaults(existing, rerun);

    @SuppressWarnings("unchecked")
    Map<String, Object> pessimistic = (Map<String, Object>) merged.get("pessimistic");
    assertThat(pessimistic)
        .containsEntry("runs", 40)
        .containsEntry("violations", 0)
        .containsEntry("duplicate_postings", 1)
        .containsEntry("duplicate_scheduled", 2);
    List<?> scenarios = (List<?>) pessimistic.get("scenarios");
    assertThat(scenarios).hasSize(2);
    assertThat(((Map<?, ?>) scenarios.get(0)).get("id")).isEqualTo(6);
    assertThat(((Map<?, ?>) scenarios.get(1)).get("id")).isEqualTo(10);
    assertThat(((Map<?, ?>) scenarios.get(1)).get("violations")).isEqualTo(0);
  }

  @Test
  void scenarioFilterParsesIdsAndRejectsUnknownOnes() {
    assertThat(EvalSections.scenarioFilter(null)).isEmpty();
    assertThat(EvalSections.scenarioFilter("10, 2")).containsExactly(10, 2);
    assertThatThrownBy(() -> EvalSections.scenarioFilter("99"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown scenario 99");
  }

  private static FaultResult result(Map<String, Integer> paymentStates, Long createdOrLost) {
    return new FaultResult(
        Scenario.ORCHESTRATOR_KILL,
        "pessimistic",
        0,
        0,
        0,
        0,
        0,
        0,
        List.of(),
        5_000_000L,
        null,
        null,
        null,
        paymentStates,
        createdOrLost,
        null);
  }
}
