package tally.proof.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ModelIsolationTest {

  private static final Pattern IMPORT =
      Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");

  @Test
  void modelImportsNoProductionCode() throws IOException {
    Path root =
        Path.of(System.getProperty("proof.root", ".."), "proof/src/test/java/tally/proof/model");
    try (Stream<Path> files = Files.walk(root)) {
      List<Path> sources =
          files
              .filter(Files::isRegularFile)
              .filter(file -> file.toString().endsWith(".java"))
              .sorted()
              .toList();
      assertThat(sources).as("model java sources").isNotEmpty();
      for (Path source : sources) {
        Matcher imports = IMPORT.matcher(Files.readString(source));
        while (imports.find()) {
          String name = imports.group(1);
          boolean forbidden =
              (name.startsWith("tally.ledger.") && !name.startsWith("tally.ledger.api."))
                  || name.startsWith("tally.platform.")
                  || name.startsWith("java.sql.")
                  || name.startsWith("javax.sql.");
          assertThat(forbidden).as("%s imports %s", source, name).isFalse();
        }
      }
    }
  }
}
