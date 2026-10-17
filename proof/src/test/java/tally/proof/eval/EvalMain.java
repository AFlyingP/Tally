package tally.proof.eval;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import tally.proof.harness.Seeds;
import tally.proof.harness.Sizes;

public final class EvalMain {

  private static final List<String> SECTIONS =
      List.of(
          "mutation",
          "coverage",
          "property",
          "concurrency",
          "baseline",
          "recon",
          "monitoring",
          "audit",
          "recovery",
          "payments",
          "events",
          "comparison",
          "faults");
  private static final List<String> STRATEGIES =
      List.of("pessimistic", "optimistic", "serializable");
  private static final List<String> COVERAGE_MODULES =
      List.of("ledger-core", "platform", "ledger", "monitor", "auth", "extbank");
  private static final Object MISSING = new Object();

  private EvalMain() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    return run(args, out, err, EvalMain::runSection);
  }

  static int run(String[] args, PrintStream out, PrintStream err, SectionRunner sectionRunner) {
    Set<String> requested = new LinkedHashSet<>();
    for (String argument : args) {
      if (!SECTIONS.contains(argument)) {
        err.println("eval failed in arguments: unknown section " + argument);
        return 2;
      }
      requested.add(argument);
    }

    Path root = Path.of(System.getProperty("proof.root", ".."));
    Path sectionsDir = root.resolve("build/eval/sections");
    long seed;
    try {
      seed = Seeds.seed();
    } catch (RuntimeException e) {
      err.println("eval failed in arguments: " + message(e));
      e.printStackTrace(err);
      return 2;
    }
    Sizes sizes = Sizes.current();
    for (String section : SECTIONS) {
      if (!requested.isEmpty() && !requested.contains(section)) {
        continue;
      }
      Path sectionFile = sectionsDir.resolve(section + ".json");
      if (requested.isEmpty() && Files.exists(sectionFile)) {
        continue;
      }
      out.println("section " + section + " started");
      long started = System.nanoTime();
      try {
        Results.writeSection(sectionsDir, section, sectionRunner.run(section, sizes, seed));
      } catch (Exception | AssertionError e) {
        err.println("eval failed in " + section + ": " + message(e));
        e.printStackTrace(err);
        return 2;
      }
      double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
      out.println(
          "section " + section + " done in " + String.format(Locale.ROOT, "%.3f", seconds) + "s");
    }

    Path resultsFile = root.resolve("build/eval/results.json");
    try {
      Map<String, Object> header = new LinkedHashMap<>();
      header.put("generated_at", Instant.now(Clock.systemUTC()).toString());
      header.put("git_commit", gitCommit(root));
      header.put("seed", seed);
      header.put("machine", MachineInfo.collect());
      Results.merge(sectionsDir, resultsFile, header);
      Map<String, Object> merged = Results.readResults(resultsFile);
      Set<String> presentSections =
          SECTIONS.stream()
              .filter(name -> Files.exists(sectionsDir.resolve(name + ".json")))
              .collect(Collectors.toCollection(LinkedHashSet::new));
      int misses = 0;
      for (String miss : targetMisses(merged, presentSections)) {
        out.println("target missed: " + miss);
        misses++;
      }
      List<String> missing =
          SECTIONS.stream()
              .filter(name -> !Files.exists(sectionsDir.resolve(name + ".json")))
              .toList();
      if (missing.isEmpty()) {
        out.println("eval complete: build/eval/results.json");
      } else {
        out.println("eval partial: missing " + String.join(", ", missing));
      }
      return misses == 0 ? 0 : 1;
    } catch (Exception | AssertionError e) {
      err.println("eval failed in results: " + message(e));
      e.printStackTrace(err);
      return 2;
    }
  }

  static List<String> targetMisses(Map<String, Object> results, Set<String> presentSections) {
    List<Target> targets = new ArrayList<>();
    addConcurrencyTargets(results, presentSections, targets);
    addPropertyTargets(results, presentSections, targets);
    addFaultTargets(results, presentSections, targets);
    addRecoveryTargets(results, presentSections, targets);
    addComparisonTargets(results, presentSections, targets);
    addMutationTargets(results, presentSections, targets);
    addCoverageTargets(results, presentSections, targets);
    addPaymentTargets(results, presentSections, targets);
    addEventTargets(results, presentSections, targets);
    addReconTargets(results, presentSections, targets);
    addMonitoringTargets(results, presentSections, targets);
    addAuditTargets(results, presentSections, targets);
    addBaselineTargets(results, presentSections, targets);

    List<String> misses = new ArrayList<>();
    for (Target target : targets) {
      if (!target.predicate().test(target.actual())) {
        misses.add(
            target.metric()
                + " expected "
                + target.expected()
                + " got "
                + display(target.actual()));
      }
    }
    return List.copyOf(misses);
  }

  private static Map<String, Object> runSection(String name, Sizes sizes, long seed) {
    return switch (name) {
      case "mutation" -> EvalSections.mutation();
      case "coverage" -> EvalSections.coverage();
      case "property" -> EvalSections.property(sizes);
      case "concurrency" -> EvalSections.concurrency(sizes, seed);
      case "baseline" -> EvalSections.baseline(seed);
      case "recon" -> EvalSections.recon(sizes, seed);
      case "monitoring" -> EvalSections.monitoring(sizes, seed);
      case "audit" -> AuditEval.run(seed);
      case "recovery" -> EvalSections.recovery(seed);
      case "payments" -> EvalSections.payments(seed);
      case "events" -> EvalSections.events(sizes, seed);
      case "comparison" -> EvalSections.comparison(sizes, seed);
      case "faults" -> EvalSections.faults(sizes, seed);
      default -> throw new IllegalArgumentException("unknown section " + name);
    };
  }

  @FunctionalInterface
  interface SectionRunner {
    Map<String, Object> run(String name, Sizes sizes, long seed);
  }

  private static void addConcurrencyTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (!presentSections.contains("concurrency")) {
      return;
    }
    for (String strategy : STRATEGIES) {
      String prefix = "concurrency." + strategy + ".";
      add(
          results,
          targets,
          prefix + "operations",
          "1000000",
          value -> equalsNumber(value, 1_000_000));
      add(results, targets, prefix + "violations", "0", value -> equalsNumber(value, 0));
      add(results, targets, prefix + "double_posted_pairs", "0", value -> equalsNumber(value, 0));
    }
  }

  private static void addPropertyTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (!presentSections.contains("property")) {
      return;
    }
    for (String strategy : STRATEGIES) {
      String prefix = "property." + strategy + ".";
      add(results, targets, prefix + "tries", "500", value -> equalsNumber(value, 500));
      add(results, targets, prefix + "sequence_length", "100", value -> equalsNumber(value, 100));
      add(results, targets, prefix + "failures", "0", value -> equalsNumber(value, 0));
    }
  }

  private static void addFaultTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (!presentSections.contains("faults")) {
      return;
    }
    for (String strategy : STRATEGIES) {
      String prefix = "faults." + strategy + ".";
      for (String metric :
          List.of("runs", "duplicate_postings", "duplicate_scheduled", "violations")) {
        int expected = metric.equals("runs") ? 220 : 0;
        add(
            results,
            targets,
            prefix + metric,
            Integer.toString(expected),
            value -> equalsNumber(value, expected));
      }
      Object scenarioObject = get(results, prefix + "scenarios");
      int scenarioCount = scenarioObject instanceof List<?> scenarios ? scenarios.size() : -1;
      addAt(
          targets,
          prefix + "scenarios.length",
          "11",
          value -> equalsNumber(value, 11),
          scenarioCount);
      if (scenarioObject instanceof List<?> scenarios) {
        for (int index = 0; index < scenarios.size(); index++) {
          Object item = scenarios.get(index);
          String itemPath = prefix + "scenarios[" + index + "].";
          addAt(
              targets,
              itemPath + "runs",
              "20",
              value -> equalsNumber(value, 20),
              field(item, "runs"));
          for (String metric : List.of("duplicate_postings", "violations", "unresolved")) {
            addAt(
                targets,
                itemPath + metric,
                "0",
                value -> equalsNumber(value, 0),
                field(item, metric));
          }
        }
      }
    }
  }

  private static void addRecoveryTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (!presentSections.contains("recovery")) {
      return;
    }
    for (String metric : List.of("api_kill_ms.runs", "db_restart_ms.runs")) {
      add(results, targets, "recovery." + metric, "20", value -> equalsNumber(value, 20));
    }
    for (String metric : List.of("api_kill_ms.median", "db_restart_ms.median")) {
      add(
          results,
          targets,
          "recovery." + metric,
          "<= 30000",
          value -> number(value) && ((Number) value).doubleValue() <= 30_000);
    }
  }

  private static void addComparisonTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (!presentSections.contains("comparison")) {
      return;
    }
    Object rowsObject = get(results, "comparison.rows");
    int rowCount = rowsObject instanceof List<?> rows ? rows.size() : -1;
    addAt(targets, "comparison.rows.length", "18", value -> equalsNumber(value, 18), rowCount);
    boolean requiredRowPresent = false;
    if (rowsObject instanceof List<?> rows) {
      for (int index = 0; index < rows.size(); index++) {
        Object row = rows.get(index);
        String prefix = "comparison.rows[" + index + "].";
        addAt(targets, prefix + "runs", "5", value -> equalsNumber(value, 5), field(row, "runs"));
        if ("pessimistic".equals(field(row, "strategy"))
            && "uniform".equals(field(row, "workload"))
            && equalsNumber(field(row, "clients"), 32)) {
          requiredRowPresent = true;
          addAt(
              targets,
              prefix + "transfers_per_s.median",
              ">= 100",
              value -> number(value) && ((Number) value).doubleValue() >= 100,
              nestedField(row, "transfers_per_s", "median"));
          addAt(
              targets,
              prefix + "p99_ms.median",
              "<= 1000",
              value -> number(value) && ((Number) value).doubleValue() <= 1_000,
              nestedField(row, "p99_ms", "median"));
        }
      }
    }
    if (!requiredRowPresent) {
      addAt(
          targets,
          "comparison.pessimistic.uniform.32",
          "row present",
          value -> Boolean.TRUE.equals(value),
          false);
    }
  }

  private static void addMutationTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (presentSections.contains("mutation")) {
      add(
          results,
          targets,
          "mutation.score",
          ">= 0.75",
          value -> number(value) && ((Number) value).doubleValue() >= 0.75);
    }
  }

  private static void addCoverageTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (!presentSections.contains("coverage")) {
      return;
    }
    if (get(results, "tests") == MISSING) {
      addAt(targets, "tests", "section data", value -> value != MISSING, MISSING);
    }
    if (get(results, "coverage") == MISSING) {
      addAt(targets, "coverage", "section data", value -> value != MISSING, MISSING);
    }
    Object packagesObject = get(results, "coverage.packages");
    if (!(packagesObject instanceof List<?> packages)) {
      addAt(
          targets,
          "coverage.packages",
          "package coverage data",
          value -> value != MISSING,
          MISSING);
      return;
    }
    Set<String> modulesWithPackages = new LinkedHashSet<>();
    for (int index = 0; index < packages.size(); index++) {
      Object row = packages.get(index);
      Object module = field(row, "module");
      Object packageName = field(row, "package");
      if (!(module instanceof String moduleName) || !(packageName instanceof String packageText)) {
        continue;
      }
      double minimum = moduleName.equals("ledger-core") ? 0.90 : 0.70;
      if (COVERAGE_MODULES.contains(moduleName)) {
        modulesWithPackages.add(moduleName);
        if (packageText.equals("tally.ledger.failpoint")) {
          continue;
        }
        addAt(
            targets,
            "coverage." + moduleName + "." + packageText + ".line_coverage",
            ">= " + String.format(Locale.ROOT, "%.2f", minimum),
            value -> number(value) && ((Number) value).doubleValue() >= minimum,
            field(row, "line_coverage"));
      }
    }
    for (String module : COVERAGE_MODULES) {
      if (!modulesWithPackages.contains(module)) {
        addAt(
            targets,
            "coverage." + module,
            "package rows",
            value -> Boolean.TRUE.equals(value),
            false);
      }
    }
  }

  private static void addPaymentTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (!presentSections.contains("payments")) {
      return;
    }
    add(results, targets, "payments.count", "2000", value -> equalsNumber(value, 2_000));
    add(results, targets, "payments.created_or_lost", "0", value -> equalsNumber(value, 0));
    add(results, targets, "payments.violations", "0", value -> equalsNumber(value, 0));
  }

  private static void addEventTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (!presentSections.contains("events")) {
      return;
    }
    add(results, targets, "events.runs", "40", value -> equalsNumber(value, 40));
    add(results, targets, "events.missing", "0", value -> equalsNumber(value, 0));
  }

  private static void addReconTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (!presentSections.contains("recon")) {
      return;
    }
    add(results, targets, "recon.seeded", "300", value -> equalsNumber(value, 300));
    add(results, targets, "recon.correct", "300", value -> equalsNumber(value, 300));
    add(results, targets, "recon.extra", "0", value -> equalsNumber(value, 0));
    add(results, targets, "recon.false_breaks", "0", value -> equalsNumber(value, 0));
  }

  private static void addMonitoringTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (!presentSections.contains("monitoring")) {
      return;
    }
    Object rulesObject = get(results, "monitoring.rules");
    int count = rulesObject instanceof List<?> rules ? rules.size() : -1;
    addAt(targets, "monitoring.rules.length", "3", value -> equalsNumber(value, 3), count);
    if (rulesObject instanceof List<?> rules) {
      for (int index = 0; index < rules.size(); index++) {
        Object rule = rules.get(index);
        String prefix = "monitoring.rules[" + index + "].";
        for (String metric : List.of("precision", "recall")) {
          addAt(
              targets,
              prefix + metric,
              "1.0",
              value -> number(value) && ((Number) value).doubleValue() == 1.0,
              field(rule, metric));
        }
      }
    }
    add(results, targets, "monitoring.double_delivery_diff", "0", value -> equalsNumber(value, 0));
  }

  private static void addAuditTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (!presentSections.contains("audit")) {
      return;
    }
    add(results, targets, "audit.changes", "1300", value -> equalsNumber(value, 1_300));
    add(results, targets, "audit.rows_per_change_min", "1", value -> equalsNumber(value, 1));
    add(results, targets, "audit.rows_per_change_max", "1", value -> equalsNumber(value, 1));
    add(results, targets, "audit.replays_with_rows", "0", value -> equalsNumber(value, 0));
    add(results, targets, "audit.tamper_cases", "20", value -> equalsNumber(value, 20));
    add(results, targets, "audit.tamper_detected", "20", value -> equalsNumber(value, 20));
  }

  private static void addBaselineTargets(
      Map<String, Object> results, Set<String> presentSections, List<Target> targets) {
    if (presentSections.contains("baseline")) {
      Object violations = get(results, "baseline.naive.violations");
      Object duplicates = get(results, "baseline.naive.double_posted_pairs");
      Object total =
          number(violations) && number(duplicates)
              ? ((Number) violations).longValue() + ((Number) duplicates).longValue()
              : MISSING;
      addAt(
          targets,
          "baseline.naive.violations + baseline.naive.double_posted_pairs",
          "> 0",
          value -> number(value) && ((Number) value).longValue() > 0,
          total);
    }
  }

  private static void add(
      Map<String, Object> results,
      List<Target> targets,
      String path,
      String expected,
      Predicate<Object> predicate) {
    addAt(targets, path, expected, predicate, get(results, path));
  }

  private static void addAt(
      List<Target> targets,
      String metric,
      String expected,
      Predicate<Object> predicate,
      Object actual) {
    targets.add(new Target(metric, expected, predicate, actual));
  }

  private static Object get(Map<String, Object> values, String path) {
    Object current = values;
    for (String part : path.split("\\.")) {
      if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
        return MISSING;
      }
      current = map.get(part);
    }
    return current;
  }

  private static Object field(Object value, String name) {
    return value instanceof Map<?, ?> map && map.containsKey(name) ? map.get(name) : MISSING;
  }

  private static Object nestedField(Object value, String object, String field) {
    return field(field(value, object), field);
  }

  private static boolean equalsNumber(Object value, long expected) {
    return number(value) && ((Number) value).longValue() == expected;
  }

  private static boolean number(Object value) {
    return value instanceof Number;
  }

  private static String display(Object value) {
    return value == MISSING ? "<missing>" : String.valueOf(value);
  }

  private static String gitCommit(Path root) {
    try {
      Process process =
          new ProcessBuilder("git", "rev-parse", "HEAD").directory(root.toFile()).start();
      byte[] output = process.getInputStream().readAllBytes();
      byte[] errors = process.getErrorStream().readAllBytes();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IllegalStateException(
            "git rev-parse HEAD failed: " + new String(errors, StandardCharsets.UTF_8).trim());
      }
      return new String(output, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new IllegalStateException("failed to read git commit", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("reading git commit interrupted", e);
    }
  }

  private static String message(Throwable exception) {
    return exception.getMessage() == null
        ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }

  private record Target(
      String metric, String expected, Predicate<Object> predicate, Object actual) {}
}
