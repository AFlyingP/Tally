package tally.proof.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.json.JsonMapper;

public final class Results {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DefaultPrettyPrinter PRETTY_PRINTER =
      new DefaultPrettyPrinter()
          .withObjectIndenter(new DefaultIndenter("  ", "\n"))
          .withArrayIndenter(new DefaultIndenter("  ", "\n"));
  private static final List<String> SECTION_ORDER =
      List.of(
          "concurrency",
          "property",
          "faults",
          "recovery",
          "comparison",
          "mutation",
          "coverage",
          "payments",
          "events",
          "recon",
          "monitoring",
          "audit",
          "baseline");

  private Results() {}

  public static void writeSection(Path dir, String name, Map<String, Object> content) {
    try {
      Files.createDirectories(dir);
      String json = JSON.writer().with(PRETTY_PRINTER).writeValueAsString(content) + "\n";
      Files.writeString(dir.resolve(name + ".json"), json, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write results section", e);
    }
  }

  public static Map<String, Object> readSection(Path dir, String name) {
    return read(dir.resolve(name + ".json"), "failed to read results section");
  }

  public static void merge(Path sectionsDir, Path out, Map<String, Object> header) {
    LinkedHashMap<String, Object> merged = new LinkedHashMap<>(header);
    try {
      for (String section : SECTION_ORDER) {
        Path file = sectionsDir.resolve(section + ".json");
        if (!Files.exists(file)) {
          continue;
        }
        Map<String, Object> content = readSection(sectionsDir, section);
        if (section.equals("coverage")) {
          for (String key : List.of("tests", "coverage")) {
            if (content.containsKey(key)) {
              merged.put(key, content.get(key));
            }
          }
        } else {
          merged.putAll(content);
        }
      }
      Files.createDirectories(out.getParent());
      String json = JSON.writer().with(PRETTY_PRINTER).writeValueAsString(merged) + "\n";
      Files.writeString(out, json, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to merge results", e);
    }
  }

  public static Map<String, Object> readResults(Path path) {
    return read(path, "failed to read results");
  }

  private static Map<String, Object> read(Path path, String failureMessage) {
    String json;
    try {
      json = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(failureMessage, e);
    }
    return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  public static Map<String, Object> stats(List<Double> values) {
    List<Double> sorted = values.stream().sorted().toList();
    int middle = sorted.size() / 2;
    double median =
        sorted.size() % 2 == 0
            ? (sorted.get(middle - 1) + sorted.get(middle)) / 2
            : sorted.get(middle);
    LinkedHashMap<String, Object> stats = new LinkedHashMap<>();
    stats.put("min", round(sorted.getFirst()));
    stats.put("median", round(median));
    stats.put("max", round(sorted.getLast()));
    return stats;
  }

  private static double round(double value) {
    return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
  }
}
