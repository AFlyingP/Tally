package tally.proof.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultsTest {

  @Test
  void statsOfOddAndEvenCounts() {
    Map<String, Object> odd = Results.stats(List.of(3.0, 1.0, 2.0));
    assertThat(odd.get("min")).isEqualTo(1.0);
    assertThat(odd.get("median")).isEqualTo(2.0);
    assertThat(odd.get("max")).isEqualTo(3.0);

    Map<String, Object> even = Results.stats(List.of(1.0, 2.0, 3.0, 4.0));
    assertThat(even.get("min")).isEqualTo(1.0);
    assertThat(even.get("median")).isEqualTo(2.5);
    assertThat(even.get("max")).isEqualTo(4.0);

    Map<String, Object> rounded = Results.stats(List.of(1.123456, 2.222222, 3.333333));
    assertThat(rounded.get("min")).isEqualTo(1.1235);
    assertThat(rounded.get("median")).isEqualTo(2.2222);
    assertThat(rounded.get("max")).isEqualTo(3.3333);
  }

  @Test
  void sectionRoundTripKeepsOrder(@TempDir Path directory) {
    LinkedHashMap<String, Object> section = new LinkedHashMap<>();
    section.put("first", 1);
    section.put("second", 2);
    section.put("third", 3);

    Results.writeSection(directory, "comparison", section);
    Map<String, Object> read = Results.readSection(directory, "comparison");

    assertThat(read.keySet()).containsExactly("first", "second", "third");
  }

  @Test
  void sectionIsWrittenWithTwoSpaceIndent(@TempDir Path directory) throws Exception {
    LinkedHashMap<String, Object> section = new LinkedHashMap<>();
    section.put("name", "load");
    section.put("rows", List.of(1, 2));

    Results.writeSection(directory, "comparison", section);

    assertThat(Files.readString(directory.resolve("comparison.json"), StandardCharsets.UTF_8))
        .isEqualTo(
            "{\n"
                + "  \"name\" : \"load\",\n"
                + "  \"rows\" : [\n"
                + "    1,\n"
                + "    2\n"
                + "  ]\n"
                + "}\n");
  }

  @Test
  void mergeOrdersHeaderAndSectionsAndSplitsCoverage(@TempDir Path directory) {
    Path sections = directory.resolve("sections");
    Path output = directory.resolve("results.json");
    Results.writeSection(sections, "baseline", Map.of("baseline", Map.of("value", 1)));
    Results.writeSection(
        sections,
        "coverage",
        Map.of("tests", Map.of("count", 5), "coverage", Map.of("packages", List.of())));
    Results.writeSection(sections, "concurrency", Map.of("concurrency", Map.of("value", 2)));
    Results.writeSection(sections, "recon", Map.of("recon", Map.of("value", 3)));
    LinkedHashMap<String, Object> header = new LinkedHashMap<>();
    header.put("generated_at", "now");
    header.put("seed", 42);

    Results.merge(sections, output, header);

    Map<String, Object> merged = Results.readResults(output);
    assertThat(merged.keySet())
        .containsExactly(
            "generated_at", "seed", "concurrency", "tests", "coverage", "recon", "baseline");
    assertThat(merged).containsKeys("tests", "coverage").doesNotContainKey("mutation");
  }
}
