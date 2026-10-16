package tally.proof.fault;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
    List<Integer> scenarios = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
    String selectedScenario = System.getProperty("proof.scenario");
    if (selectedScenario != null) {
      int id = Integer.parseInt(selectedScenario);
      scenarios = scenarios.stream().filter(scenario -> scenario == id).toList();
    }
    try (FaultStack stack = FaultStack.start(strategy, stackParts(scenarios))) {
      for (int scenario : scenarios) {
        for (int run = 0; run < sizes.faultRuns(); run++) {
          try {
            FaultResult result = runScenario(scenario, stack, strategy, sizes, Seeds.seed() + run);
            if (scenario == 11) {
              System.out.println(
                  "scenario=11 strategy="
                      + strategy
                      + " missing_alerts="
                      + result.monitor().get("missing_alerts")
                      + " extra_alerts="
                      + result.monitor().get("extra_alerts")
                      + " duplicate_alerts="
                      + result.monitor().get("duplicate_alerts"));
            } else {
              String fields =
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
                      + result.violations().size();
              if (result.duplicateScheduled() != null) {
                fields += " sched_duplicates=" + result.duplicateScheduled();
              }
              if (result.missingEvents() != null) {
                fields += " missing_events=" + result.missingEvents();
              }
              if (result.duplicateEvents() != null) {
                fields += " duplicate_events=" + result.duplicateEvents();
              }
              System.out.println(fields);
              result.assertClean();
            }
          } finally {
            stopWorkers(stack);
          }
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
      case 6 -> PaymentScenarios.schedulerKill(stack, strategy, sizes, seed);
      case 7 -> PaymentScenarios.relayKill(stack, strategy, sizes, seed);
      case 8 -> PaymentScenarios.brokerRestart(stack, strategy, sizes, seed);
      case 9 -> PaymentScenarios.extbankFaults(stack, strategy, sizes, seed);
      case 10 -> PaymentScenarios.orchestratorKill(stack, strategy, sizes, seed);
      case 11 -> MonitorScenario.monitorKill(stack, strategy, sizes, seed);
      default -> throw new IllegalArgumentException("unknown fault scenario: " + scenario);
    };
  }

  private static Set<FaultStack.Part> stackParts(List<Integer> scenarios) {
    EnumSet<FaultStack.Part> parts =
        EnumSet.of(FaultStack.Part.POSTGRES, FaultStack.Part.TOXIPROXY, FaultStack.Part.LEDGER);
    if (scenarios.contains(6)) {
      parts.add(FaultStack.Part.WORKER);
      parts.add(FaultStack.Part.WORKER2);
    }
    if (scenarios.contains(7) || scenarios.contains(8)) {
      parts.add(FaultStack.Part.KAFKA);
      parts.add(FaultStack.Part.WORKER);
    }
    if (scenarios.contains(9) || scenarios.contains(10)) {
      parts.add(FaultStack.Part.EXTBANK);
      parts.add(FaultStack.Part.WORKER);
    }
    if (scenarios.contains(11)) {
      parts.add(FaultStack.Part.KAFKA);
      parts.add(FaultStack.Part.MONITOR);
    }
    return parts;
  }

  private static void stopWorkers(FaultStack stack) {
    for (String alias : List.of("worker", "worker2")) {
      try {
        if (stack.running(alias)) {
          stack.kill(alias);
        }
      } catch (IllegalStateException ignored) {
        return;
      }
    }
  }
}
