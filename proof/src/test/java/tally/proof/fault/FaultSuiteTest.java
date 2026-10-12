package tally.proof.fault;

import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tally.proof.harness.Seeds;
import tally.proof.harness.Sizes;

@Tag("fault")
class FaultSuiteTest {

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
    Seeds.print("fault");
    Sizes sizes = Sizes.current();
    List<Integer> scenarios = List.of(1, 2, 3, 4, 5);
    String selectedScenario = System.getProperty("proof.scenario");
    if (selectedScenario != null) {
      int id = Integer.parseInt(selectedScenario);
      scenarios = scenarios.stream().filter(scenario -> scenario == id).toList();
    }
    try (FaultStack stack = FaultStack.start(strategy, EnumSet.allOf(FaultStack.Part.class))) {
      for (int scenario : scenarios) {
        for (int run = 0; run < sizes.faultRuns(); run++) {
          FaultResult result = runScenario(scenario, stack, strategy, sizes, Seeds.seed() + run);
          System.out.println(
              "scenario="
                  + scenario
                  + " strategy="
                  + strategy
                  + " operations="
                  + result.operations()
                  + " posted="
                  + result.posted()
                  + " rejected="
                  + result.rejected()
                  + " duplicates="
                  + result.duplicatePostings()
                  + " violations="
                  + result.violations().size());
          result.assertClean();
        }
      }
    }
  }

  private static FaultResult runScenario(
      int scenario, FaultStack stack, String strategy, Sizes sizes, long seed) {
    return switch (scenario) {
      case 1 -> LedgerScenarios.apiKill(stack, strategy, sizes, seed);
      case 2 -> LedgerScenarios.connectionCut(stack, strategy, sizes, seed);
      case 3 -> LedgerScenarios.slowDatabase(stack, strategy, sizes, seed);
      case 4 -> LedgerScenarios.databaseRestart(stack, strategy, sizes, seed);
      case 5 -> LedgerScenarios.sameKeyBurst(stack, strategy, sizes, seed);
      default -> throw new IllegalArgumentException("unknown fault scenario: " + scenario);
    };
  }
}
