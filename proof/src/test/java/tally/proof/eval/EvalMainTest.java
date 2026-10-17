package tally.proof.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvalMainTest {

  @Test
  void targetCheckReportsMissingValueAndKeepsPassingTargetQuiet() {
    Map<String, Object> results = Map.of("payments", Map.of("count", 2_000, "created_or_lost", 0));

    assertThat(EvalMain.targetMisses(results, Set.of("payments")))
        .containsExactly("payments.violations expected 0 got <missing>");
  }

  @Test
  void presentSectionWithMissingTopLevelKeyReportsItsTargets() {
    assertThat(EvalMain.targetMisses(Map.of(), Set.of("payments")))
        .containsExactly(
            "payments.count expected 2000 got <missing>",
            "payments.created_or_lost expected 0 got <missing>",
            "payments.violations expected 0 got <missing>");
  }

  @Test
  void comparisonReportsMissingRequiredPessimisticUniform32Row() {
    Map<String, Object> results = Map.of("comparison", Map.of("rows", List.of()));

    assertThat(EvalMain.targetMisses(results, Set.of("comparison")))
        .contains("comparison.pessimistic.uniform.32 expected row present got false");
  }

  @Test
  void coverageReportsModulesWithoutPackageRows() {
    Map<String, Object> results =
        Map.of(
            "tests", Map.of("count", 1),
            "coverage",
                Map.of(
                    "packages",
                    List.of(
                        Map.of(
                            "module", "ledger-core",
                            "package", "tally.ledger.core",
                            "line_coverage", 0.95))));

    assertThat(EvalMain.targetMisses(results, Set.of("coverage")))
        .contains("coverage.platform expected package rows got false");
  }

  @Test
  void unknownArgumentFailsBeforeRunningSections() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
      int exitCode = EvalMain.run(new String[] {"bogus"}, out, err);

      assertThat(exitCode).isEqualTo(2);
      assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
      assertThat(errors.toString(StandardCharsets.UTF_8))
          .contains("eval failed in arguments: unknown section bogus");
    }
  }

  @Test
  void assertionErrorFromSectionReturnsExitCodeTwo() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
      int exitCode =
          EvalMain.run(
              new String[] {"payments"},
              out,
              err,
              (name, sizes, seed) -> {
                throw new AssertionError("section assertion failed");
              });

      assertThat(exitCode).isEqualTo(2);
      assertThat(output.toString(StandardCharsets.UTF_8)).contains("section payments started");
      assertThat(errors.toString(StandardCharsets.UTF_8))
          .contains("eval failed in payments: section assertion failed")
          .contains("java.lang.AssertionError: section assertion failed");
    }
  }
}
