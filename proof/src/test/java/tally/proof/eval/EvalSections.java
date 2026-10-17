package tally.proof.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import tally.proof.concurrency.ConcurrencyRun;
import tally.proof.concurrency.Workload;
import tally.proof.fault.FaultResult;
import tally.proof.fault.FaultStack;
import tally.proof.fault.FaultSuiteTest;
import tally.proof.fault.Scenario;
import tally.proof.harness.InProcessLedger;
import tally.proof.harness.Sizes;
import tally.proof.naive.NaiveConfig;
import tally.proof.seeded.SeededMonitoringTest;
import tally.proof.seeded.SeededReconTest;

public final class EvalSections {

  private static final List<String> STRATEGIES =
      List.of("pessimistic", "optimistic", "serializable");

  private EvalSections() {}

  public static Map<String, Object> concurrency(Sizes sizes, long seed) {
    Map<String, Object> strategies = new LinkedHashMap<>();
    for (String strategy : STRATEGIES) {
      InProcessLedger ledger = InProcessLedger.start(strategy, Clock.systemUTC());
      try {
        ConcurrencyRun.Result uniform =
            ConcurrencyRun.run(
                ledger,
                Workload.UNIFORM,
                sizes.concurrencyOperations() / 2,
                sizes.concurrencyThreads(),
                seed);
        ConcurrencyRun.Result hot =
            ConcurrencyRun.run(
                ledger,
                Workload.HOT,
                sizes.concurrencyOperations() / 2,
                sizes.concurrencyThreads(),
                seed);
        strategies.put(strategy, concurrencyResult(uniform, hot));
      } finally {
        ledger.stop();
      }
    }
    return Map.of("concurrency", strategies);
  }

  public static Map<String, Object> baseline(long seed) {
    InProcessLedger ledger = InProcessLedger.start("naive", Clock.systemUTC(), NaiveConfig.class);
    try {
      ConcurrencyRun.Result result =
          ConcurrencyRun.run(ledger, Workload.HOT, 20_000, 32, seed, true);
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("operations", result.operations());
      values.put("violations", result.violations().size());
      values.put("double_posted_pairs", result.doublePostedPairs());
      return Map.of("baseline", Map.of("naive", values));
    } finally {
      ledger.stop();
    }
  }

  public static Map<String, Object> property(Sizes sizes) {
    Map<String, Object> strategies = new LinkedHashMap<>();
    for (String strategy : STRATEGIES) {
      String previousStrategy = System.getProperty("proof.strategy");
      System.setProperty("proof.strategy", strategy);
      long failures;
      try {
        failures = runProperties(strategy);
      } finally {
        if (previousStrategy == null) {
          System.clearProperty("proof.strategy");
        } else {
          System.setProperty("proof.strategy", previousStrategy);
        }
      }
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("tries", 500);
      values.put("sequence_length", sizes.sequenceLength());
      values.put("failures", failures);
      strategies.put(strategy, values);
    }
    return Map.of("property", strategies);
  }

  public static Map<String, Object> recon(Sizes sizes, long seed) {
    return Map.of("recon", SeededReconTest.measure(sizes, seed));
  }

  public static Map<String, Object> monitoring(Sizes sizes, long seed) {
    return Map.of("monitoring", SeededMonitoringTest.measure(sizes, seed));
  }

  public static Map<String, Object> faults(Sizes sizes, long seed) {
    String parallelValue = System.getenv("TALLY_EVAL_FAULT_PARALLEL");
    int parallelism = 1;
    if (parallelValue != null) {
      if (!parallelValue.equals("1") && !parallelValue.equals("3")) {
        throw new IllegalArgumentException("TALLY_EVAL_FAULT_PARALLEL must be 1 or 3");
      }
      parallelism = Integer.parseInt(parallelValue);
    }
    long memoryMb =
        MachineInfo.collect().get("docker_memory_mb") instanceof Number number
            ? number.longValue()
            : 0;
    if (parallelValue == null) {
      parallelism = memoryMb >= 20_000 ? 3 : 1;
    }
    System.out.println("faults parallel=" + parallelism + " docker_memory_mb=" + memoryMb);
    List<Scenario> scenarios = Arrays.asList(Scenario.values());
    Map<String, FaultStack> stacks = new LinkedHashMap<>();
    try {
      for (String strategy : STRATEGIES) {
        stacks.put(strategy, FaultStack.start(strategy, EnumSet.allOf(FaultStack.Part.class)));
      }
      Map<String, Object> results = new LinkedHashMap<>();
      if (parallelism == 1) {
        for (String strategy : STRATEGIES) {
          results.put(
              strategy, faultStrategy(stacks.get(strategy), scenarios, strategy, sizes, seed));
        }
      } else {
        AtomicInteger threadNumber = new AtomicInteger();
        try (ExecutorService executor =
            Executors.newFixedThreadPool(
                3,
                task -> {
                  Thread thread = new Thread(task);
                  thread.setName("faults-" + STRATEGIES.get(threadNumber.getAndIncrement()));
                  return thread;
                })) {
          Map<String, Future<Map<String, Object>>> futures = new LinkedHashMap<>();
          for (String strategy : STRATEGIES) {
            futures.put(
                strategy,
                executor.submit(
                    () -> faultStrategy(stacks.get(strategy), scenarios, strategy, sizes, seed)));
          }
          RuntimeException failure = null;
          for (String strategy : STRATEGIES) {
            try {
              results.put(strategy, futures.get(strategy).get());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              failure = new IllegalStateException("fault runs interrupted", e);
            } catch (ExecutionException e) {
              failure = new IllegalStateException("fault run failed", e.getCause());
            }
          }
          if (failure != null) {
            throw failure;
          }
        }
      }
      return Map.of("faults", results);
    } finally {
      RuntimeException failure = null;
      for (String strategy : STRATEGIES) {
        FaultStack stack = stacks.get(strategy);
        if (stack != null) {
          try {
            stack.close();
          } catch (RuntimeException e) {
            if (failure == null) {
              failure = e;
            } else {
              failure.addSuppressed(e);
            }
          }
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }

  public static Map<String, Object> events(Sizes sizes, long seed) {
    try (FaultStack stack =
        FaultStack.start(
            "pessimistic",
            EnumSet.of(
                FaultStack.Part.POSTGRES,
                FaultStack.Part.TOXIPROXY,
                FaultStack.Part.LEDGER,
                FaultStack.Part.KAFKA,
                FaultStack.Part.WORKER))) {
      int missing = 0;
      int duplicates = 0;
      for (Scenario scenario : List.of(Scenario.RELAY_KILL, Scenario.BROKER_RESTART)) {
        for (int run = 0; run < sizes.faultRuns(); run++) {
          FaultResult result =
              FaultSuiteTest.runScenario(stack, scenario, "pessimistic", sizes, seed + run);
          System.out.println(
              "scenario="
                  + scenario.id()
                  + " strategy=pessimistic operations="
                  + result.operations()
                  + " posted="
                  + result.posted()
                  + " rejected="
                  + result.rejected()
                  + " duplicates="
                  + result.duplicatePostings()
                  + " violations="
                  + result.violations().size());
          missing += result.missingEvents() == null ? 0 : result.missingEvents();
          duplicates += result.duplicateEvents() == null ? 0 : result.duplicateEvents();
        }
      }
      return Map.of(
          "events",
          Map.of("runs", sizes.faultRuns() * 2, "missing", missing, "duplicates", duplicates));
    }
  }

  public static Map<String, Object> comparison(Sizes sizes, long seed) {
    return Map.of("comparison", LoadComparison.run(sizes, seed));
  }

  public static Map<String, Object> mutation() {
    Path root = Path.of(System.getProperty("proof.root", ".."));
    runGradle(root, List.of(":ledger-core:pitest", "-PpitThreshold=0"), 30);
    return CoverageReport.mutation(root.resolve("ledger-core/build/reports/pitest/mutations.xml"));
  }

  public static Map<String, Object> coverage() {
    Path root = Path.of(System.getProperty("proof.root", ".."));
    runGradle(root, List.of("test", "integrationTest", "jacocoTestReport"), 60);
    Map<String, Object> result = new LinkedHashMap<>();
    result.putAll(CoverageReport.tests(root));
    result.put("coverage", Map.of("packages", CoverageReport.coverage(root)));
    return result;
  }

  private static Map<String, Object> concurrencyResult(
      ConcurrencyRun.Result first, ConcurrencyRun.Result second) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("operations", first.operations() + second.operations());
    values.put("posted", first.posted() + second.posted());
    values.put("insufficient", first.insufficient() + second.insufficient());
    values.put("exhausted", first.exhausted() + second.exhausted());
    values.put("violations", first.violations().size() + second.violations().size());
    values.put("pairs", first.pairs() + second.pairs());
    values.put("double_posted_pairs", first.doublePostedPairs() + second.doublePostedPairs());
    values.put("seconds", first.seconds() + second.seconds());
    return values;
  }

  private static Map<String, Object> faultStrategy(
      FaultStack stack, List<Scenario> scenarios, String strategy, Sizes sizes, long seed) {
    List<Map<String, Object>> scenarioResults = new ArrayList<>();
    int runs = 0;
    int duplicatePostings = 0;
    int duplicateScheduled = 0;
    int violations = 0;
    for (Scenario scenario : scenarios) {
      int scenarioDuplicates = 0;
      int scenarioDuplicateScheduled = 0;
      int scenarioViolations = 0;
      int unresolved = 0;
      int scenarioRuns = 0;
      for (int run = 0; run < sizes.faultRuns(); run++) {
        FaultResult result =
            FaultSuiteTest.runScenario(stack, scenario, strategy, sizes, seed + run);
        System.out.println(
            "scenario="
                + scenario.id()
                + " strategy="
                + strategy
                + " operations="
                + result.operations()
                + " posted="
                + result.posted()
                + " rejected="
                + result.rejected()
                + " duplicates="
                + result.duplicatePostings()
                + " violations="
                + result.violations().size()
                + " unresolved="
                + result.unresolved());
        runs++;
        scenarioRuns++;
        scenarioDuplicates += result.duplicatePostings();
        scenarioViolations += result.violations().size();
        unresolved += result.unresolved();
        if (result.duplicateScheduled() != null) {
          scenarioDuplicateScheduled += result.duplicateScheduled();
          duplicateScheduled += result.duplicateScheduled();
        }
      }
      duplicatePostings += scenarioDuplicates;
      violations += scenarioViolations;
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("id", scenario.id());
      values.put("runs", scenarioRuns);
      values.put("duplicate_postings", scenarioDuplicates);
      values.put("violations", scenarioViolations);
      values.put("unresolved", unresolved);
      if (scenario == Scenario.SCHEDULER_KILL) {
        values.put("duplicate_scheduled", scenarioDuplicateScheduled);
      }
      scenarioResults.add(values);
    }
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("runs", runs);
    values.put("duplicate_postings", duplicatePostings);
    values.put("duplicate_scheduled", duplicateScheduled);
    values.put("violations", violations);
    values.put("scenarios", scenarioResults);
    return values;
  }

  private static void runGradle(Path root, List<String> arguments, int timeoutMinutes) {
    List<String> command = new ArrayList<>();
    command.add(System.getProperty("os.name").startsWith("Windows") ? "gradlew.bat" : "./gradlew");
    command.add("--project-cache-dir");
    command.add("build/eval/gradle-cache");
    command.addAll(arguments);
    try {
      Process process = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
      if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IllegalStateException("gradle command timed out");
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "gradle command failed with exit code " + process.exitValue());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to start gradle", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("gradle command interrupted", e);
    }
  }

  private static long runProperties(String strategy) {
    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(
                DiscoverySelectors.selectClass("tally.proof.property.LedgerSequenceProperties"))
            .configurationParameter("proof.strategy", strategy)
            .configurationParameter("proof.size", "eval")
            .configurationParameter("jqwik.tries.default", "500")
            .build();
    Launcher launcher = LauncherFactory.create();
    SummaryGeneratingListener listener = new SummaryGeneratingListener();
    launcher.registerTestExecutionListeners(listener);
    launcher.execute(request);
    return listener.getSummary().getTestsFailedCount();
  }
}
