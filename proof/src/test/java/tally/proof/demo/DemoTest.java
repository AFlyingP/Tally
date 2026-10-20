package tally.proof.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DemoTest {

  @Test
  void cannotCreateCastFailsBeforeRunningDemo() throws Exception {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try (PrintStream replacement = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setErr(replacement);
      int result =
          Demo.run(new String[] {"--cast", Path.of("missing-demo-directory", "x.cast").toString()});
      assertEquals(1, result);
    } finally {
      System.setErr(originalErr);
    }
    assertTrue(captured.toString(StandardCharsets.UTF_8).contains("demo failed at step 0:"));
  }
}
