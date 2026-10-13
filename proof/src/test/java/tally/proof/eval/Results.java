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
    String json;
    try {
      json = Files.readString(dir.resolve(name + ".json"), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read results section", e);
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
