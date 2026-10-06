package tally.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NoFloatingPointTest {

  @Test
  void ledgerSourcesContainNoFloatingPoint() throws IOException {
    assertNoMatches(
        List.of(Path.of("src/main/java"), Path.of("../ledger-core/src/main/java")),
        Pattern.compile("\\b(float|double|Float|Double)\\b|BigDecimal\\.doubleValue"));
  }

  @Test
  void mainSourcesReadTimeOnlyFromTheClock() throws IOException {
    List<Path> roots =
        Stream.of("ledger", "ledger-core", "platform", "monitor", "auth", "extbank")
            .map(module -> Path.of("..", module, "src/main/java"))
            .toList();
    assertNoMatches(
        roots,
        Pattern.compile(
            "Instant\\.now\\(\\)|System\\.currentTimeMillis\\(\\)|LocalDate\\.now\\(\\)|new Date\\(\\)"));
  }

  private static void assertNoMatches(List<Path> roots, Pattern forbidden) throws IOException {
    for (Path root : roots) {
      try (Stream<Path> files = Files.walk(root)) {
        List<Path> sources =
            files
                .filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".java"))
                .sorted()
                .toList();
        assertThat(sources).as("java sources under %s", root).isNotEmpty();
        for (Path source : sources) {
          List<String> lines = Files.readAllLines(source);
          for (int line = 0; line < lines.size(); line++) {
            assertThat(forbidden.matcher(lines.get(line)).find())
                .as("%s:%s", source, line + 1)
                .isFalse();
          }
        }
      }
    }
  }
}
