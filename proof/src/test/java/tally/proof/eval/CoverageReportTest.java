package tally.proof.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageReportTest {

  @Test
  void mutationCountsDetectedStatusesAndExcludesNonViableAndOtherPackages(@TempDir Path directory)
      throws Exception {
    Path mutations = directory.resolve("mutations.xml");
    Files.writeString(
        mutations,
        "<mutations>"
            + mutation("tally.ledger.core.Account", "KILLED")
            + mutation("tally.ledger.core.Account", "TIMED_OUT")
            + mutation("tally.ledger.core.Account", "MEMORY_ERROR")
            + mutation("tally.ledger.core.Account", "RUN_ERROR")
            + mutation("tally.ledger.core.Account", "NO_COVERAGE")
            + mutation("tally.ledger.core.Account", "SURVIVED")
            + mutation("tally.ledger.core.Account", "UNEXPECTED")
            + mutation("tally.ledger.core.Account", "NON_VIABLE")
            + mutation("tally.ledger.other.Account", "KILLED")
            + "</mutations>");

    assertThat(CoverageReport.mutation(mutations))
        .containsEntry(
            "mutation",
            Map.of(
                "mutants", 7,
                "killed", 4,
                "survived", 2,
                "no_coverage", 1,
                "score", 0.5714));
  }

  @Test
  void mutationFailsWhenThereAreNoViableMutantsInLedgerCore(@TempDir Path directory)
      throws Exception {
    Path mutations = directory.resolve("mutations.xml");
    Files.writeString(
        mutations,
        "<mutations>"
            + mutation("tally.ledger.core.Account", "NON_VIABLE")
            + mutation("tally.ledger.other.Account", "KILLED")
            + "</mutations>");

    assertThatThrownBy(() -> CoverageReport.mutation(mutations))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no mutants of tally.ledger.core in " + mutations);
  }

  @Test
  void testsCountOnlyTestAndIntegrationResultsAndSubtractSkipped(@TempDir Path directory)
      throws Exception {
    writeSuite(directory.resolve("ledger/build/test-results/test/TEST-unit.xml"), 8, 2);
    writeSuite(directory.resolve("ledger/build/test-results/integrationTest/TEST-it.xml"), 7, 1);
    writeSuite(directory.resolve("ledger/build/test-results/proofTest/TEST-proof.xml"), 100, 0);
    writeSuite(directory.resolve("ledger/build/test-results/e2eTest/TEST-e2e.xml"), 100, 0);

    assertThat(CoverageReport.tests(directory))
        .containsEntry("tests", Map.of("count", 12, "by_module", Map.of("ledger", 12)));
  }

  private static String mutation(String mutatedClass, String status) {
    return "<mutation status=\""
        + status
        + "\"><mutatedClass>"
        + mutatedClass
        + "</mutatedClass></mutation>";
  }

  private static void writeSuite(Path path, int tests, int skipped) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, "<testsuite tests=\"" + tests + "\" skipped=\"" + skipped + "\"/>\n");
  }
}
