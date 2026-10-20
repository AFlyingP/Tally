package tally.proof.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CastWriterTest {

  @Test
  void writesHeaderAndEscapedEvents() throws Exception {
    Path file = Files.createTempFile("cast-writer", ".cast");
    try (CastWriter cast = new CastWriter(file, 100, 32)) {
      cast.line("quote \" slash \\ newline\n control\u0001");
    }

    var lines = Files.readAllLines(file);
    assertTrue(
        lines
            .get(0)
            .matches(
                "\\{\\\"version\\\": 2, \\\"width\\\": 100, \\\"height\\\": 32, \\\"timestamp\\\": \\d+}"));
    assertTrue(lines.get(1).matches("\\[\\d+\\.\\d{3}, \\\"o\\\", .*\\]"));
    assertTrue(lines.get(1).contains("quote \\\" slash \\\\ newline\\n control\\u0001\\r\\n"));
    assertEquals(2, lines.size());
  }
}
