package tally.proof.eval;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ResultsRenderer {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String COMPARISON_START = "<!-- comparison:start -->";
  private static final String COMPARISON_END = "<!-- comparison:end -->";
  private static final String RESULTS_START = "<!-- results:start -->";
  private static final String RESULTS_END = "<!-- results:end -->";

  private ResultsRenderer() {}

  public static String comparisonTable(JsonNode results) {
    StringBuilder table =
        new StringBuilder(
            "| Strategy | Workload | Clients | Transfers/s | p50 ms | p99 ms | Retries | Exhausted |\n"
                + "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |");
    JsonNode rows = results.path("comparison").path("rows");
    for (JsonNode row : rows) {
      table
          .append('\n')
          .append("| ")
          .append(row.path("strategy").asString())
          .append(" | ")
          .append(row.path("workload").asString())
          .append(" | ")
          .append(row.path("clients").asInt())
          .append(" | ")
          .append(decimal(row.path("transfers_per_s").path("median"), 1))
          .append(" | ")
          .append(decimal(row.path("p50_ms").path("median"), 1))
          .append(" | ")
          .append(decimal(row.path("p99_ms").path("median"), 1))
          .append(" | ")
          .append(integer(row.path("retries").path("median")))
          .append(" | ")
          .append(integer(row.path("exhausted").path("median")))
          .append(" |");
    }
    JsonNode first = rows.get(0);
    JsonNode machine = results.path("machine");
    table
        .append("\n\nMedian of ")
        .append(first.path("runs").asInt())
        .append(" runs of 30 seconds each, on ")
        .append(machine.path("cpu_model").asString())
        .append(", ")
        .append(machine.path("cpu_cores").asInt())
        .append(" cores, ")
        .append(machine.path("memory_mb").asInt())
        .append(" MB, Docker limit ")
        .append(machine.path("docker_memory_mb").asInt())
        .append(" MB.");
    return table.toString();
  }

  public static String readmeBlock(JsonNode results) {
    List<String[]> rows = new ArrayList<>();
    JsonNode concurrency = results.path("concurrency");
    List<String> operations = new ArrayList<>();
    long violations = 0;
    for (String strategy : List.of("pessimistic", "optimistic", "serializable")) {
      JsonNode values = concurrency.path(strategy);
      operations.add(values.path("operations").asString());
      violations += values.path("violations").asLong();
    }
    String ops =
        operations.stream().distinct().count() == 1
            ? operations.getFirst()
            : String.join(" / ", operations);
    add(rows, "Concurrent operations per strategy", ops);
    add(rows, "Invariant violations after those runs", Long.toString(violations));
    JsonNode faults = results.path("faults");
    long faultRuns = 0;
    long duplicatePostings = 0;
    long duplicateScheduled = 0;
    for (JsonNode strategy : faults) {
      faultRuns += strategy.path("runs").asLong();
      duplicatePostings += strategy.path("duplicate_postings").asLong();
      duplicateScheduled += strategy.path("duplicate_scheduled").asLong();
    }
    add(rows, "Fault scenarios run", "11 scenarios x 20 runs x 3 strategies = " + faultRuns);
    add(rows, "Duplicate postings in fault runs", Long.toString(duplicatePostings));
    add(rows, "Duplicate scheduled payments (scenario 6)", Long.toString(duplicateScheduled));
    JsonNode payments = results.path("payments");
    JsonNode shares = payments.path("shares");
    add(
        rows,
        "External payments",
        payments.path("count").asString()
            + " (settled "
            + percent(shares.path("SETTLED"))
            + ", compensated "
            + percent(shares.path("REJECTED_COMPENSATED"))
            + ", manual review "
            + percent(shares.path("MANUAL_REVIEW"))
            + ", failed "
            + percent(shares.path("FAILED"))
            + ")");
    add(
        rows,
        "Money created or lost in those payments",
        payments.path("created_or_lost").asString() + " minor units");
    JsonNode events = results.path("events");
    add(
        rows,
        "Events missing after relay and broker faults",
        events.path("missing").asString()
            + " (duplicate deliveries: "
            + events.path("duplicates").asString()
            + ")");
    JsonNode recon = results.path("recon");
    add(
        rows,
        "Reconciliation breaks found with the right type",
        recon.path("correct").asString()
            + " of "
            + recon.path("seeded").asString()
            + " (false breaks on "
            + recon.path("clean_files").asString()
            + " unaltered files: "
            + recon.path("false_breaks").asString()
            + ")");
    for (String rule : List.of("R1", "R2", "R3")) {
      JsonNode metric = rule(results.path("monitoring"), rule);
      add(
          rows,
          "Monitoring precision / recall, " + rule,
          decimal(metric.path("precision"), 2) + " / " + decimal(metric.path("recall"), 2));
    }
    add(
        rows,
        "Alert difference with every event delivered twice",
        results.path("monitoring").path("double_delivery_diff").asString());
    JsonNode audit = results.path("audit");
    String minimum = audit.path("rows_per_change_min").asString();
    String maximum = audit.path("rows_per_change_max").asString();
    add(
        rows,
        "Audit rows per state change",
        minimum.equals(maximum) ? minimum : minimum + " to " + maximum);
    add(
        rows,
        "Tampered audit rows detected",
        audit.path("tamper_detected").asString() + " of " + audit.path("tamper_cases").asString());
    add(
        rows,
        "Recovery after API kill, median",
        results.path("recovery").path("api_kill_ms").path("median").asString() + " ms");
    add(
        rows,
        "Recovery after database restart, median",
        results.path("recovery").path("db_restart_ms").path("median").asString() + " ms");
    JsonNode property = results.path("property").path("pessimistic");
    add(
        rows,
        "Property test sequences per strategy",
        property.path("tries").asString()
            + " sequences of "
            + property.path("sequence_length").asString()
            + " operations");
    JsonNode mutation = results.path("mutation");
    add(
        rows,
        "Mutation score, ledger core",
        percent(mutation.path("score"))
            + " ("
            + mutation.path("killed").asString()
            + " of "
            + mutation.path("mutants").asString()
            + " mutants)");
    add(rows, "Tests", results.path("tests").path("count").asString());
    JsonNode baseline = results.path("baseline").path("naive");
    add(
        rows,
        "Naive baseline: violations found",
        baseline.path("violations").asString()
            + " (double-posted pairs: "
            + baseline.path("double_posted_pairs").asString()
            + ")");
    StringBuilder block = new StringBuilder("| Measure | Result |\n| --- | --- |");
    for (String[] row : rows) {
      block.append("\n| ").append(row[0]).append(" | ").append(row[1]).append(" |");
    }
    String commit = results.path("git_commit").asString();
    String generated = results.path("generated_at").asString();
    String date = generated.length() >= 10 ? generated.substring(0, 10) : generated;
    return block
        .append("\n\nMeasured at commit ")
        .append(commit.substring(0, Math.min(7, commit.length())))
        .append(" on ")
        .append(date)
        .append(". Reproduce with: sh task.sh eval")
        .toString();
  }

  public static String replaceBlock(
      String document, String startMarker, String endMarker, String content) {
    int start = document.indexOf(startMarker);
    if (start < 0) {
      throw new IllegalStateException("marker not found: " + startMarker);
    }
    int end = document.indexOf(endMarker, start + startMarker.length());
    if (end < 0 || document.indexOf(endMarker) < start) {
      throw new IllegalStateException("marker not found: " + endMarker);
    }
    return document.substring(0, start + startMarker.length())
        + "\n\n"
        + content
        + "\n\n"
        + document.substring(end);
  }

  public static void main(String[] args) throws IOException {
    Path root = Path.of(System.getProperty("proof.root", "."));
    Path resultFile = root.resolve("build/eval/results.json");
    if (!Files.exists(resultFile)) {
      System.err.println("render failed: build/eval/results.json not found, run: sh task.sh eval");
      System.exit(2);
    }
    boolean check = args.length == 1 && args[0].equals("--check");
    JsonNode results = JSON.readTree(Files.readString(resultFile, StandardCharsets.UTF_8));
    List<RenderFile> files = new ArrayList<>();
    files.add(
        new RenderFile(
            root.resolve("docs/architecture.md"),
            COMPARISON_START,
            COMPARISON_END,
            comparisonTable(results)));
    Path readme = root.resolve("README.md");
    if (Files.exists(readme)) {
      String content = Files.readString(readme, StandardCharsets.UTF_8);
      if (content.contains(RESULTS_START) || content.contains(RESULTS_END)) {
        files.add(new RenderFile(readme, RESULTS_START, RESULTS_END, readmeBlock(results)));
      }
    }
    boolean stale = false;
    for (RenderFile file : files) {
      String document = Files.readString(file.path(), StandardCharsets.UTF_8);
      String replaced = replaceBlock(document, file.start(), file.end(), file.content());
      String name = root.relativize(file.path()).toString().replace('\\', '/');
      if (check) {
        if (!document.equals(replaced)) {
          System.out.println("stale: " + name);
          stale = true;
        }
      } else {
        Files.writeString(file.path(), replaced, StandardCharsets.UTF_8);
        System.out.println("rendered " + name);
      }
    }
    if (check && !stale) {
      System.out.println("results match");
    }
    if (check && stale) {
      System.exit(1);
    }
  }

  private static JsonNode rule(JsonNode monitoring, String name) {
    for (JsonNode rule : monitoring.path("rules")) {
      if (name.equals(rule.path("rule").asString())) {
        return rule;
      }
    }
    return JSON.nullNode();
  }

  private static void add(List<String[]> rows, String name, String value) {
    rows.add(new String[] {name, value});
  }

  private static String percent(JsonNode value) {
    BigDecimal percentage =
        new BigDecimal(value.asString())
            .multiply(BigDecimal.valueOf(100))
            .setScale(1, RoundingMode.HALF_UP);
    return String.format(Locale.ROOT, "%.1f%%", percentage);
  }

  private static String decimal(JsonNode value, int scale) {
    BigDecimal rounded = new BigDecimal(value.asString()).setScale(scale, RoundingMode.HALF_UP);
    return String.format(Locale.ROOT, "%." + scale + "f", rounded);
  }

  private static String integer(JsonNode value) {
    return new BigDecimal(value.asString()).setScale(0, RoundingMode.HALF_UP).toPlainString();
  }

  private record RenderFile(Path path, String start, String end, String content) {}
}
