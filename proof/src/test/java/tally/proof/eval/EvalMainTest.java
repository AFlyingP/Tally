package tally.proof.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvalMainTest {

  @Test
  void targetCheckReportsMissingValueAndKeepsPassingTargetQuiet() {
    Map<String, Object> results = Map.of("payments", Map.of("count", 2_000, "created_or_lost", 0));

    assertThat(EvalMain.targetMisses(results))
        .containsExactly("payments.violations expected 0 got <missing>");
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
}
