package tally.proof.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import tally.platform.security.Role;
import tally.proof.concurrency.ConcurrencyRun;
import tally.proof.concurrency.Workload;
import tally.proof.fault.FaultResult;
import tally.proof.fault.FaultStack;
import tally.proof.fault.FaultSuiteTest;
import tally.proof.fault.Scenario;
import tally.proof.harness.Http;
import tally.proof.harness.InProcessLedger;
import tally.proof.harness.LedgerClient;
import tally.proof.harness.Sizes;
import tally.proof.naive.NaiveConfig;
import tally.proof.seeded.SeededMonitoringTest;
import tally.proof.seeded.SeededReconTest;
import tools.jackson.databind.json.JsonMapper;

public final class EvalSections {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern TRIES_LINE = Pattern.compile("(?m)^\\s*tries\\s*=\\s*(\\d+)");
  private static final List<String> STRATEGIES =
      List.of("pessimistic", "optimistic", "serializable");

  private EvalSections() {}

  public static Map<String, Object> concurrency(Sizes sizes, long seed) {
    Map<String, Object> strategies = new LinkedHashMap<>();
    for (String strategy : STRATEGIES) {
      ConcurrencyRun.Result uniform = runConcurrency(strategy, Workload.UNIFORM, sizes, seed);
      ConcurrencyRun.Result hot = runConcurrency(strategy, Workload.HOT, sizes, seed);
      strategies.put(strategy, concurrencyResult(uniform, hot));
    }
    return Map.of("concurrency", strategies);
  }

  private static ConcurrencyRun.Result runConcurrency(
      String strategy, Workload workload, Sizes sizes, long seed) {
    InProcessLedger ledger = InProcessLedger.start(strategy, Clock.systemUTC());
    try {
      return ConcurrencyRun.run(
          ledger, workload, sizes.concurrencyOperations() / 2, sizes.concurrencyThreads(), seed);
    } finally {
      ledger.stop();
    }
  }

  public static Map<String, Object> baseline(long seed) {
    InProcessLedger ledger = InProcessLedger.start("naive", Clock.systemUTC(), NaiveConfig.class);
    try {
      ConcurrencyRun.Result result =
          ConcurrencyRun.run(ledger, Workload.HOT, 20_000, 32, seed, true);
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("operations", executedOperations(result));
      values.put("violations", violationCount(result));
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
      PropertyResult result;
      try {
        result = runProperties(strategy);
      } finally {
        if (previousStrategy == null) {
          System.clearProperty("proof.strategy");
        } else {
          System.setProperty("proof.strategy", previousStrategy);
        }
      }
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("tries", result.tries());
      values.put("sequence_length", sizes.sequenceLength());
      values.put("failures", result.failures());
      strategies.put(strategy, values);
    }
    return Map.of("property", strategies);
  }

  public static Map<String, Object> recon(Sizes sizes, long seed) {
    return Map.of("recon", SeededReconTest.measure(sizes, seed));
  }

  public static Map<String, Object> monitoring(Sizes sizes, long seed) {
    Map<String, Object> measured = SeededMonitoringTest.measure(sizes, seed);
    List<?> measuredRules = (List<?>) measured.get("rules");
    List<Map<String, Object>> rules = new ArrayList<>(measuredRules.size());
    for (Object measuredRule : measuredRules) {
      Map<?, ?> values = (Map<?, ?>) measuredRule;
      Map<String, Object> rule = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : values.entrySet()) {
        String key = (String) entry.getKey();
        Object value = entry.getValue();
        if (key.equals("precision") || key.equals("recall")) {
          value =
              BigDecimal.valueOf(((Number) value).doubleValue())
                  .setScale(4, RoundingMode.HALF_UP)
                  .doubleValue();
        }
        rule.put(key, value);
      }
      rules.add(rule);
    }
    Map<String, Object> result = new LinkedHashMap<>(measured);
    result.put("rules", List.copyOf(rules));
    return Map.of("monitoring", result);
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
      Map<String, Object> results = new LinkedHashMap<>();
      if (parallelism == 1) {
        for (String strategy : STRATEGIES) {
          FaultStack stack = FaultStack.start(strategy, EnumSet.allOf(FaultStack.Part.class));
          stacks.put(strategy, stack);
          try {
            results.put(strategy, faultStrategy(stack, scenarios, strategy, sizes, seed));
          } finally {
            stack.close();
            stacks.remove(strategy);
          }
        }
      } else {
        for (String strategy : STRATEGIES) {
          stacks.put(strategy, FaultStack.start(strategy, EnumSet.allOf(FaultStack.Part.class)));
        }
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
      int runs = 0;
      for (Scenario scenario : List.of(Scenario.RELAY_KILL, Scenario.BROKER_RESTART)) {
        for (int run = 0; run < sizes.faultRuns(); run++) {
          runs++;
          FaultResult result;
          try {
            result = FaultSuiteTest.runScenario(stack, scenario, "pessimistic", sizes, seed + run);
          } catch (AssertionError | IllegalStateException failure) {
            missing++;
            System.out.println(
                "scenario="
                    + scenario.id()
                    + " strategy=pessimistic run="
                    + (run + 1)
                    + " failed: "
                    + failure.getMessage());
            continue;
          }
          RunCounts counts = countRun(result, scenario);
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
                  + counts.violations()
                  + (counts.reasons().isEmpty() ? "" : " reasons=" + counts.reasons()));
          missing += counts.missing();
          duplicates += counts.duplicates();
        }
      }
      return Map.of("events", Map.of("runs", runs, "missing", missing, "duplicates", duplicates));
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

  public static Map<String, Object> recovery(long seed) {
    List<Double> apiTimes = new ArrayList<>();
    List<Double> dbTimes = new ArrayList<>();
    try (FaultStack stack =
        FaultStack.start(
            "pessimistic",
            EnumSet.of(
                FaultStack.Part.POSTGRES, FaultStack.Part.TOXIPROXY, FaultStack.Part.LEDGER))) {
      String token = stack.issuer().token(Role.ADMIN);
      LedgerClient client = new LedgerClient(stack.ledgerUrl(), token);
      Http.Response created = client.createAccount(0, true);
      if (created.status() != 201) {
        throw new IllegalStateException("recovery account creation failed: " + created.status());
      }
      String accountId = created.json().get("id").asString();
      for (int run = 0; run < 20; run++) {
        stack.kill("ledger");
        long started = System.nanoTime();
        stack.startNoWait("ledger");
        apiTimes.add(awaitAccount(stack, accountId, token, started));
      }
      for (int run = 0; run < 20; run++) {
        long started = System.nanoTime();
        stack.restart("postgres", 10);
        dbTimes.add(awaitAccount(stack, accountId, token, started));
      }
    }
    return Map.of(
        "recovery",
        Map.of(
            "api_kill_ms", withRuns(apiTimes),
            "db_restart_ms", withRuns(dbTimes)));
  }

  public static Map<String, Object> payments(long seed) {
    try (FaultStack stack =
        FaultStack.start(
            "pessimistic",
            EnumSet.of(
                FaultStack.Part.POSTGRES,
                FaultStack.Part.EXTBANK,
                FaultStack.Part.LEDGER,
                FaultStack.Part.WORKER))) {
      PaymentRun run = new PaymentRun(stack, seed);
      return Map.of("payments", run.execute());
    }
  }

  private static Map<String, Object> withRuns(List<Double> times) {
    Map<String, Object> result = new LinkedHashMap<>(Results.stats(times));
    result.put("runs", times.size());
    return result;
  }

  private static double awaitAccount(
      FaultStack stack, String accountId, String token, long started) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    long deadline = started + Duration.ofSeconds(120).toNanos();
    try {
      while (System.nanoTime() < deadline) {
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(stack.ledgerUrl() + "/v1/accounts/" + accountId))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build();
        try {
          HttpResponse<Void> response =
              client.send(request, HttpResponse.BodyHandlers.discarding());
          if (response.statusCode() == 200) {
            return (System.nanoTime() - started) / 1_000_000.0;
          }
        } catch (IOException ignored) {
          // The service can refuse connections while its container is starting.
        }
        TimeUnit.MILLISECONDS.sleep(50);
      }
      throw new IllegalStateException("account endpoint did not recover within 120 seconds");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("recovery polling interrupted", e);
    } finally {
      client.close();
    }
  }

  private static final class PaymentRun {
    private static final int PAYMENT_COUNT = 2_000;
    private static final int SOURCE_COUNT = 100;
    private static final long SOURCE_FUNDING = 1_000_000;
    private static final Duration PAYMENT_WINDOW = Duration.ofSeconds(120);
    private final FaultStack stack;
    private final SplittableRandom random;
    private final LedgerClient client;
    private final List<UUID> sources = new ArrayList<>();
    private final List<UUID> payments = Collections.synchronizedList(new ArrayList<>());
    private final List<Future<?>> requests = new ArrayList<>();
    private ExecutorService clients;
    private long deposits;

    private PaymentRun(FaultStack stack, long seed) {
      this.stack = stack;
      random = new SplittableRandom(seed);
      client = new LedgerClient(stack.ledgerUrl(), stack.issuer().token(Role.ADMIN));
    }

    private Map<String, Object> execute() {
      try {
        setup();
        stack.withEnv("worker", "TALLY_LEDGER_ROLES", "orchestrator,expiry");
        stack.withEnv("worker", "TALLY_FAILPOINT", "");
        stack.start("worker");
        clients = Executors.newFixedThreadPool(8);
        long started = System.nanoTime();
        AtomicReference<Throwable> restartFailure = new AtomicReference<>();
        Thread restarts =
            new Thread(
                () -> {
                  try {
                    for (int index = 0; index < 3; index++) {
                      sleepUntil(started + Duration.ofSeconds(30L * (index + 1)).toNanos());
                      restartAtFailpoint(index);
                    }
                  } catch (Throwable failure) {
                    restartFailure.set(failure);
                  }
                },
                "payment-failpoints");
        restarts.start();
        for (int index = 0; index < PAYMENT_COUNT; index++) {
          long target = started + PAYMENT_WINDOW.toNanos() * index / PAYMENT_COUNT;
          sleepUntil(target);
          int paymentIndex = index;
          UUID source = sources.get(random.nextInt(sources.size()));
          long amount = random.nextLong(1, 5_001);
          String creditor = String.format("CRED-%04d", index % 60);
          requests.add(clients.submit(() -> createPayment(paymentIndex, source, amount, creditor)));
        }
        sleepUntil(started + PAYMENT_WINDOW.toNanos());
        restarts.join();
        if (restartFailure.get() != null) {
          throw new IllegalStateException("payment worker restart failed", restartFailure.get());
        }
        for (Future<?> request : requests) {
          request.get();
        }
        clients.close();
        clients = null;
        awaitPayments();
        Map<String, Integer> states = states();
        long money = conservationDifference();
        int violations = stack.checker(false, false).size();
        Map<String, Double> shares = new LinkedHashMap<>();
        for (String state : List.of("SETTLED", "REJECTED_COMPENSATED", "MANUAL_REVIEW", "FAILED")) {
          shares.put(
              state,
              BigDecimal.valueOf(states.getOrDefault(state, 0) / (double) PAYMENT_COUNT)
                  .setScale(4, RoundingMode.HALF_UP)
                  .doubleValue());
        }
        return Map.of(
            "count", payments.size(),
            "shares", shares,
            "created_or_lost", money,
            "violations", violations);
      } catch (Exception e) {
        throw new IllegalStateException("payment evaluation failed", e);
      } finally {
        if (clients != null) {
          clients.shutdownNow();
        }
        stopWorker(stack);
        clearRules(stack);
      }
    }

    private void setup() throws SQLException {
      clearRules(stack);
      for (int index = 0; index < SOURCE_COUNT; index++) {
        Http.Response response = client.createAccount(0, true);
        requireStatus(response, 201, "payment source creation");
        UUID account = UUID.fromString(response.json().get("id").asString());
        sources.add(account);
        requireStatus(
            client.deposit("eval-payment-fund-" + index, account, SOURCE_FUNDING),
            201,
            "payment source funding");
        deposits += SOURCE_FUNDING;
      }
      List<ExtbankRule> rules = new ArrayList<>();
      addRules(rules, "ACCEPT", 0, 10);
      addRules(rules, "REJECT", 0, 10);
      addRules(rules, "DELAY", 100, 5);
      addRules(rules, "DELAY", 600, 5);
      addRules(rules, "DROP_RESPONSE", 400, 10);
      addRules(rules, "CONFIRM_TWICE", 0, 10);
      addRules(rules, "SERVER_ERROR", 0, 10);
      for (int index = 0; index < rules.size(); index++) {
        ExtbankRule rule = rules.get(index);
        addRule(String.format("CRED-%04d", index), rule.behaviour(), rule.delayMs(), null);
      }
    }

    private static void addRules(
        List<ExtbankRule> rules, String behaviour, int delayMs, int count) {
      for (int index = 0; index < count; index++) {
        rules.add(new ExtbankRule(behaviour, delayMs));
      }
    }

    private void createPayment(int index, UUID source, long amount, String creditor) {
      Http.Response response =
          client.createPayment("eval-payment-" + index, source, amount, creditor);
      requireStatus(response, 202, "payment creation");
      payments.add(UUID.fromString(response.json().get("id").asString()));
    }

    private void restartAtFailpoint(int index) {
      String[] failpoints = {"payment.after_hold", "payment.after_capture", "payment.after_submit"};
      stack.kill("worker");
      stack.withEnv("worker", "TALLY_FAILPOINT", failpoints[index]);
      stack.start("worker");
      try {
        stack.awaitExit("worker", Duration.ofSeconds(30));
      } catch (IllegalStateException e) {
        if (!stack.running("worker")) {
          throw e;
        }
        stack.kill("worker");
      }
      stack.withEnv("worker", "TALLY_FAILPOINT", "");
      stack.start("worker");
    }

    private void awaitPayments() {
      long end = System.nanoTime() + Duration.ofSeconds(300).toNanos();
      while (System.nanoTime() < end) {
        if (terminalCount() == PAYMENT_COUNT) {
          return;
        }
        try {
          TimeUnit.MILLISECONDS.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("payment wait interrupted", e);
        }
      }
      throw new IllegalStateException("payments did not become terminal within 300 seconds");
    }

    private int terminalCount() {
      return paymentCount("state IN ('SETTLED','REJECTED_COMPENSATED','MANUAL_REVIEW','FAILED')");
    }

    private int paymentCount(String predicate) {
      try (Connection connection = stack.jdbc();
          PreparedStatement query =
              connection.prepareStatement(
                  "SELECT count(*) FROM ledger.external_payment WHERE " + predicate)) {
        try (ResultSet rows = query.executeQuery()) {
          rows.next();
          return rows.getInt(1);
        }
      } catch (SQLException e) {
        throw new IllegalStateException("failed to read payments", e);
      }
    }

    private Map<String, Integer> states() {
      try (Connection connection = stack.jdbc();
          PreparedStatement query =
              connection.prepareStatement(
                  "SELECT state, count(*) FROM ledger.external_payment GROUP BY state")) {
        Map<String, Integer> result = new HashMap<>();
        try (ResultSet rows = query.executeQuery()) {
          while (rows.next()) {
            result.put(rows.getString(1), rows.getInt(2));
          }
        }
        return result;
      } catch (SQLException e) {
        throw new IllegalStateException("failed to read payment states", e);
      }
    }

    private long conservationDifference() throws SQLException {
      try (Connection connection = stack.jdbc();
          PreparedStatement query =
              connection.prepareStatement(
                  "SELECT (SELECT coalesce(sum(balance),0) FROM ledger.account WHERE kind='CUSTOMER') "
                      + "+ (SELECT coalesce(sum(balance),0) FROM ledger.account WHERE kind='CLEARING' AND currency='EUR') "
                      + "+ (SELECT coalesce(sum(amount),0) FROM ledger.external_payment WHERE state='SETTLED')")) {
        try (ResultSet rows = query.executeQuery()) {
          rows.next();
          return Math.abs(deposits - rows.getLong(1));
        }
      }
    }

    private void addRule(String creditor, String behaviour, int delay, Integer remaining) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("creditor_account", creditor);
      body.put("behaviour", behaviour);
      body.put("delay_ms", delay);
      if (remaining != null) {
        body.put("remaining", remaining);
      }
      Http.Response response =
          Http.post(
              stack.extbankUrl() + "/control/rules",
              null,
              JSON.writeValueAsString(body),
              "X-Api-Key",
              stack.extbankApiKey());
      requireStatus(response, 201, "external bank rule");
    }

    private static void clearRules(FaultStack stack) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(stack.extbankUrl() + "/control/rules"))
              .header("X-Api-Key", stack.extbankApiKey())
              .DELETE()
              .build();
      try {
        int status =
            HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode();
        if (status != 204) {
          throw new IllegalStateException("clear external bank rules returned " + status);
        }
      } catch (IOException e) {
        throw new IllegalStateException("failed to clear external bank rules", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("clearing external bank rules interrupted", e);
      }
    }

    private static void requireStatus(Http.Response response, int status, String action) {
      if (response.status() != status) {
        throw new IllegalStateException(
            action + " returned " + response.status() + ": " + response.body());
      }
    }

    private static void stopWorker(FaultStack stack) {
      if (stack.running("worker")) {
        stack.kill("worker");
      }
    }

    private static void sleepUntil(long deadline) {
      long remaining;
      while ((remaining = deadline - System.nanoTime()) > 0) {
        LockSupport.parkNanos(remaining);
        if (Thread.interrupted()) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("payment schedule interrupted");
        }
      }
    }

    private record ExtbankRule(String behaviour, int delayMs) {}
  }

  private static Map<String, Object> concurrencyResult(
      ConcurrencyRun.Result first, ConcurrencyRun.Result second) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("operations", executedOperations(first) + executedOperations(second));
    values.put("posted", first.posted() + second.posted());
    values.put("insufficient", first.insufficient() + second.insufficient());
    values.put("exhausted", first.exhausted() + second.exhausted());
    values.put("violations", violationCount(first) + violationCount(second));
    values.put("pairs", first.pairs() + second.pairs());
    values.put("double_posted_pairs", first.doublePostedPairs() + second.doublePostedPairs());
    values.put("seconds", first.seconds() + second.seconds());
    return values;
  }

  private static long executedOperations(ConcurrencyRun.Result result) {
    return result.posted() + result.insufficient() + result.exhausted() + result.unexpected();
  }

  private static long violationCount(ConcurrencyRun.Result result) {
    return result.violations().size()
        + (result.posted() != result.transferRows() ? 1 : 0)
        + (result.balanceSum() != result.depositedSum() ? 1 : 0)
        + result.unexpected();
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
        scenarioRuns++;
        runs++;
        FaultResult result;
        try {
          result = FaultSuiteTest.runScenario(stack, scenario, strategy, sizes, seed + run);
        } catch (AssertionError | IllegalStateException failure) {
          System.out.println(
              "scenario="
                  + scenario.id()
                  + " strategy="
                  + strategy
                  + " run="
                  + (run + 1)
                  + " failed: "
                  + failure.getMessage());
          scenarioViolations++;
          continue;
        }
        RunCounts counts = countRun(result, scenario);
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
                + counts.violations()
                + " unresolved="
                + result.unresolved()
                + (counts.reasons().isEmpty() ? "" : " reasons=" + counts.reasons()));
        scenarioDuplicates += result.duplicatePostings();
        scenarioViolations += counts.violations();
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

  private static RunCounts countRun(FaultResult result, Scenario scenario) {
    int violations = result.violations().size();
    int missing = 0;
    int duplicates = 0;
    List<String> reasons = new ArrayList<>();
    boolean eventMeasurementsRequired =
        scenario == Scenario.RELAY_KILL || scenario == Scenario.BROKER_RESTART;
    if (result.missingPostings() > 0) {
      violations++;
      reasons.add("missing_postings=" + result.missingPostings());
    }
    if (scenario != Scenario.MONITOR_KILL && result.balanceSum() != 5_000_000L) {
      violations++;
      reasons.add("balance_sum=" + result.balanceSum());
    }
    if (result.missingEvents() != null) {
      missing += result.missingEvents();
      if (result.missingEvents() > 0) {
        violations++;
        reasons.add("missing_events=" + result.missingEvents());
      }
    } else if (eventMeasurementsRequired) {
      reasons.add("missing_events=unmeasured");
    }
    if (result.duplicateEvents() != null) {
      duplicates += result.duplicateEvents();
      if (result.duplicateEvents() > 0) {
        reasons.add("duplicate_events=" + result.duplicateEvents());
      }
    } else if (eventMeasurementsRequired) {
      reasons.add("duplicate_events=unmeasured");
    }
    if (eventMeasurementsRequired
        && (result.missingEvents() == null || result.duplicateEvents() == null)) {
      violations++;
      missing++;
    }
    if (result.createdOrLost() != null && result.createdOrLost() != 0) {
      violations++;
      reasons.add("created_or_lost=" + result.createdOrLost());
    }
    switch (scenario) {
      case SCHEDULER_KILL -> {
        if (result.duplicateScheduled() == null) {
          violations++;
          reasons.add("duplicate_scheduled=unmeasured");
        }
      }
      case RELAY_KILL, BROKER_RESTART -> {
        // Required event measurements are counted above.
      }
      case EXTBANK_FAULTS, ORCHESTRATOR_KILL -> {
        if (result.createdOrLost() == null) {
          violations++;
          reasons.add("created_or_lost=unmeasured");
        }
      }
      case MONITOR_KILL -> {
        if (result.monitor() == null) {
          violations++;
          reasons.add("monitor=unmeasured");
        } else {
          for (String key : List.of("missing_alerts", "extra_alerts", "duplicate_alerts")) {
            Integer count = result.monitor().get(key);
            if (count == null) {
              violations++;
              reasons.add(key + "=unmeasured");
            } else if (count != 0) {
              violations++;
              reasons.add(key + "=" + count);
            }
          }
        }
      }
      default -> {}
    }
    if (scenario == Scenario.SCHEDULER_KILL
        && result.duplicateScheduled() != null
        && result.duplicateScheduled() > 0) {
      reasons.add("duplicate_scheduled=" + result.duplicateScheduled());
    }
    return new RunCounts(violations, missing, duplicates, String.join(",", reasons));
  }

  private record RunCounts(int violations, int missing, int duplicates, String reasons) {}

  private static void runGradle(Path root, List<String> arguments, int timeoutMinutes) {
    List<String> command = new ArrayList<>();
    boolean isWindows = System.getProperty("os.name").startsWith("Windows");
    command.add(root.resolve(isWindows ? "gradlew.bat" : "gradlew").toAbsolutePath().toString());
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

  private static PropertyResult runProperties(String strategy) {
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
    List<Integer> tries = new ArrayList<>();
    Set<String> reportKeys = new LinkedHashSet<>();
    launcher.registerTestExecutionListeners(listener);
    launcher.registerTestExecutionListeners(
        new TestExecutionListener() {
          @Override
          public void reportingEntryPublished(TestIdentifier testIdentifier, ReportEntry entry) {
            for (Map.Entry<String, String> report : entry.getKeyValuePairs().entrySet()) {
              reportKeys.add(report.getKey());
              String value = report.getValue();
              if (report.getKey().equals("tries")) {
                tries.add(Integer.parseInt(value));
              }
              Matcher matcher = TRIES_LINE.matcher(value);
              while (matcher.find()) {
                tries.add(Integer.parseInt(matcher.group(1)));
              }
            }
          }
        });
    launcher.execute(request);
    var summary = listener.getSummary();
    if (summary.getTestsFoundCount() == 0) {
      throw new IllegalStateException("no jqwik tests ran for " + strategy);
    }
    if (tries.isEmpty()) {
      throw new IllegalStateException(
          "no jqwik tries reported for " + strategy + "; report entry keys: " + reportKeys);
    }
    long failures =
        summary.getTestsFailedCount()
            + summary.getTestsAbortedCount()
            + summary.getContainersFailedCount();
    return new PropertyResult(
        tries.stream().mapToInt(Integer::intValue).min().orElseThrow(), failures);
  }

  private record PropertyResult(int tries, long failures) {}
}
