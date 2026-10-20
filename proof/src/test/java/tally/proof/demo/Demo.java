package tally.proof.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import tally.platform.security.Role;
import tally.proof.concurrency.Workload;
import tally.proof.harness.Http;
import tally.proof.harness.Http.Response;
import tally.proof.harness.Seeds;
import tally.proof.harness.Stack;
import tally.proof.seeded.StatementGenerator;
import tally.proof.seeded.StatementGenerator.Seeded;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs the interactive stack demonstration. */
public final class Demo {

  private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(2000);
  private static final List<String> STRATEGIES =
      List.of("pessimistic", "optimistic", "serializable");
  private static final List<String> BREAK_TYPES =
      List.of(
          "AMOUNT_MISMATCH",
          "CURRENCY_MISMATCH",
          "DATE_MISMATCH",
          "DUPLICATE_IN_STATEMENT",
          "MISSING_IN_LEDGER",
          "MISSING_IN_STATEMENT");

  public static void main(String[] args) {
    System.exit(run(args));
  }

  public static int run(String[] args) {
    Path castPath = null;
    for (int index = 0; index < args.length; index++) {
      if (args[index].equals("--cast") && index + 1 < args.length) {
        castPath = Path.of(args[++index]);
      } else {
        System.err.println("demo failed at step 0: unknown or incomplete option " + args[index]);
        return 1;
      }
    }

    CastWriter cast = null;
    if (castPath != null) {
      try {
        cast = new CastWriter(castPath, 100, 32);
      } catch (IOException e) {
        System.err.println("demo failed at step 0: cannot write cast file: " + castPath);
        return 1;
      }
    }

    Demo demo = new Demo(cast);
    try {
      demo.runStep(1, demo::step1);
      demo.runStep(2, demo::step2);
      demo.runStep(3, demo::step3);
      demo.runStep(4, demo::step4);
      demo.runStep(5, demo::step5);
      demo.runStep(6, demo::step6);
      demo.runStep(7, demo::step7);
      demo.runStep(8, demo::step8);
      demo.runStep(9, demo::step9);
      demo.runStep(10, demo::step10);
      demo.runStep(11, demo::step11);
      demo.runStep(12, demo::step12);
      System.out.println("demo ok");
      return 0;
    } catch (DemoFailure e) {
      System.err.println("demo failed at step " + e.step + ": " + e.getMessage());
      return 1;
    } finally {
      if (cast != null) {
        try {
          cast.close();
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
  }

  private final CastWriter cast;
  private final String runId = UUID.randomUUID().toString().replace("-", "");
  private final JsonMapper json = JsonMapper.builder().build();
  private final List<UUID> accounts = new ArrayList<>();
  private String operatorToken;
  private String complianceToken;
  private String adminToken;
  private LoadRun hotLoad;
  private ScheduledExecutorService reporter;
  private long hotLoadDeadline;
  private final ThreadLocal<Integer> activeStep = ThreadLocal.withInitial(() -> 0);

  private Demo(CastWriter cast) {
    this.cast = cast;
  }

  private void runStep(int step, StepAction action) {
    activeStep.set(step);
    try {
      action.run();
    } catch (DemoFailure e) {
      throw e;
    } catch (Exception e) {
      throw new DemoFailure(step, message(e));
    }
  }

  private synchronized void say(int step, String text) {
    String line = "[" + step + "] " + text;
    System.out.println(line);
    if (cast != null) {
      try {
        cast.line(line);
      } catch (IOException e) {
        throw new DemoFailure(step, "cannot write cast output: " + message(e));
      }
    }
  }

  private void expect(int step, boolean condition, String reason) {
    if (!condition) {
      throw new DemoFailure(step, reason);
    }
  }

  private void step1() throws Exception {
    Result compose =
        Exec.run(
            List.of("docker", "compose", "up", "-d", "--wait", "--wait-timeout", "180"),
            Map.of(),
            Duration.ofSeconds(200));
    expect(1, compose.exitCode() == 0, "docker compose up failed: " + compose.output().trim());
    for (int port = 8080; port <= 8084; port++) {
      Response ready = request("GET", "http://localhost:" + port + "/ready", null, null, Map.of());
      expect(
          1, ready.status() == 200, "service on port " + port + " is not ready: " + ready.status());
    }
    Response cleared =
        request("DELETE", "http://localhost:8083/control/rules", null, null, extbankHeaders());
    expect(1, cleared.status() == 204, "extbank rules could not be cleared: " + cleared.status());
    say(1, "stack ready");
  }

  private void step2() {
    operatorToken = Stack.token(Role.OPERATOR);
    for (int index = 0; index < 10; index++) {
      Response response =
          request(
              "POST", ledger("/v1/accounts"), operatorToken, "{\"currency\":\"EUR\"}", Map.of());
      expect(2, response.status() == 201, "account creation failed: " + response.body());
      accounts.add(uuid(response.json().get("id"), 2, "account id"));
    }
    for (int index = 0; index < accounts.size(); index++) {
      Response response =
          request(
              "POST",
              ledger("/v1/deposits"),
              operatorToken,
              json.writeValueAsString(
                  Map.of(
                      "account_id", accounts.get(index), "amount", 1_000_000, "currency", "EUR")),
              Map.of("Idempotency-Key", "demo-" + runId + "-dep-" + index));
      expect(
          2,
          response.status() == 201,
          "deposit failed for account " + index + ": " + response.body());
    }
    say(2, "accounts funded: 10");
  }

  private void step3() throws Exception {
    hotLoad = startLoad(Duration.ofSeconds(20), 3);
    hotLoadDeadline = hotLoad.workloadDeadlineNanos();
    reporter = Executors.newSingleThreadScheduledExecutor();
    reporter.scheduleAtFixedRate(() -> say(3, hotLoad.counts()), 1, 1, TimeUnit.SECONDS);
  }

  private void step4() throws Exception {
    try {
      Thread.sleep(8_000);
      Result killed =
          Exec.run(
              List.of("docker", "kill", "--signal=KILL", "tally-ledger-1"),
              Map.of(),
              Duration.ofSeconds(10));
      expect(4, killed.exitCode() == 0, "ledger kill failed: " + killed.output().trim());
      say(4, "ledger killed");
      Result started =
          Exec.run(List.of("docker", "start", "tally-ledger-1"), Map.of(), Duration.ofSeconds(30));
      expect(4, started.exitCode() == 0, "ledger start failed: " + started.output().trim());
      long readyStarted = System.nanoTime();
      long readyDeadline = readyStarted + Duration.ofSeconds(120).toNanos();
      boolean ready = false;
      while (System.nanoTime() < readyDeadline) {
        try {
          if (request("GET", ledger("/ready"), null, null, Map.of()).status() == 200) {
            ready = true;
            break;
          }
        } catch (DemoFailure ignored) {
          // Continue polling while the API process restarts.
        }
        Thread.sleep(50);
      }
      expect(4, ready, "ledger did not become ready within 120 seconds");
      long readyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readyStarted);
      say(4, "ledger ready after " + readyMs + " ms");
      long remaining = hotLoadDeadline - System.nanoTime();
      if (remaining > 0) {
        TimeUnit.NANOSECONDS.sleep(remaining);
      }
      hotLoad.await(Duration.ofSeconds(60));
      expect(3, hotLoad.failure() == null, "hot workload failed: " + hotLoad.failure());
      expect(3, hotLoad.posted() > 0, "hot workload posted no transfers");
      expect(
          3,
          hotLoad.unresolved() == 0,
          "hot workload has unanswered operations: " + hotLoad.unresolved());
    } finally {
      reporter.shutdownNow();
      hotLoad.close();
    }
  }

  private void step5() throws Exception {
    UUID source = accounts.get(0);
    UUID destination = accounts.get(1);
    String key = "demo-" + runId + "-same-key";
    CountDownLatch ready = new CountDownLatch(10);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
      List<Future<Response>> responses = new ArrayList<>();
      for (int index = 0; index < 10; index++) {
        responses.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return transfer(key, source, destination, 50);
                }));
      }
      ready.await();
      start.countDown();
      Set<String> transactionIds = new HashSet<>();
      for (Future<Response> future : responses) {
        Response response = future.get();
        expect(5, response.status() == 201, "same-key transfer failed: " + response.body());
        transactionIds.add(response.json().get("id").asString());
      }
      expect(5, transactionIds.size() == 1, "same key returned multiple transaction IDs");
      String transactionId = transactionIds.iterator().next();
      List<JsonNode> entries = allEntries(source);
      long matching =
          entries.stream()
              .filter(entry -> entry.get("transaction_id").asString().equals(transactionId))
              .count();
      expect(5, matching == 1, "same-key transaction has " + matching + " source entries");
    }
    say(5, "same key x10: transactions=1");
  }

  private void step6() {
    createExtbankRule("DEMO-REJECT", "REJECT", 0, 6);
    Response submitted = createPayment("reject", byAvailable(6).get(0), 1_000, "DEMO-REJECT", 6);
    expect(6, submitted.status() == 202, "payment submission failed: " + submitted.body());
    JsonNode initial = submitted.json();
    String id = initial.get("id").asString();
    JsonNode payment = pollPayment(id, Duration.ofSeconds(60), 6, 100);
    expect(
        6,
        payment.get("state").asString().equals("REJECTED_COMPENSATED"),
        "payment did not reach REJECTED_COMPENSATED");
    JsonNode resolution = payment.get("resolution_transaction_id");
    expect(
        6, resolution != null && !resolution.isNull(), "payment has no compensation transaction");
    say(6, "payment " + id + " state=REJECTED_COMPENSATED compensation=" + resolution.asString());
  }

  private void step7() {
    createExtbankRule("DEMO-SLOW", "DELAY", 1_500, 7);
    Response submitted = createPayment("slow", byAvailable(7).get(0), 1_000, "DEMO-SLOW", 7);
    expect(7, submitted.status() == 202, "payment submission failed: " + submitted.body());
    String id = submitted.json().get("id").asString();
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    JsonNode payment = null;
    while (System.nanoTime() < deadline) {
      payment = getPayment(id, 7);
      if (payment.get("state").asString().equals("SUBMITTING")) {
        break;
      }
      sleep(20, 7);
    }
    expect(
        7,
        payment != null && payment.get("state").asString().equals("SUBMITTING"),
        "payment never reached SUBMITTING");
    Result killed =
        Exec.run(
            List.of("docker", "kill", "--signal=KILL", "tally-ledger-worker-1"),
            Map.of(),
            Duration.ofSeconds(10));
    expect(7, killed.exitCode() == 0, "worker kill failed: " + killed.output().trim());
    say(7, "worker killed at state=SUBMITTING");
    Result started =
        Exec.run(
            List.of("docker", "start", "tally-ledger-worker-1"), Map.of(), Duration.ofSeconds(30));
    expect(7, started.exitCode() == 0, "worker start failed: " + started.output().trim());
    payment = pollPayment(id, Duration.ofSeconds(120), 7, 500);
    expect(7, payment.get("state").asString().equals("SETTLED"), "payment did not reach SETTLED");
    say(7, "payment " + id + " state=SETTLED");
  }

  private void step8() {
    Response created =
        request("POST", ledger("/v1/accounts"), operatorToken, "{\"currency\":\"EUR\"}", Map.of());
    expect(8, created.status() == 201, "monitoring account creation failed: " + created.body());
    UUID account = uuid(created.json().get("id"), 8, "account id");
    Response deposit =
        request(
            "POST",
            ledger("/v1/deposits"),
            operatorToken,
            json.writeValueAsString(
                Map.of("account_id", account, "amount", 10_000, "currency", "EUR")),
            Map.of("Idempotency-Key", "demo-" + runId + "-monitor-dep"));
    expect(8, deposit.status() == 201, "monitoring account deposit failed: " + deposit.body());
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    for (int index = 0; index < 12 && System.nanoTime() < end; index++) {
      UUID destination = accounts.get(index % accounts.size());
      Response response = transfer("monitor-" + index, account, destination, 1);
      expect(8, response.status() == 201, "monitoring transfer failed: " + response.body());
    }
    expect(8, System.nanoTime() <= end, "12 monitoring transfers did not finish within 5 seconds");
    complianceToken = Stack.token(Role.COMPLIANCE);
    String casesUrl = "http://localhost:8082/v1/cases?rule=R1&account_id=" + account + "&limit=200";
    long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
    JsonNode cases = null;
    while (System.nanoTime() < deadline) {
      Response response = request("GET", casesUrl, complianceToken, null, Map.of());
      expect(8, response.status() == 200, "case lookup failed: " + response.body());
      cases = response.json().get("items");
      if (cases.size() >= 1) {
        break;
      }
      sleep(1_000, 8);
    }
    expect(8, cases != null && cases.size() == 1, "expected one R1 case for account " + account);
    String caseId = cases.get(0).get("id").asString();
    say(8, "case opened: rule=R1 account=" + account + " case=" + caseId);
  }

  private void step9() {
    adminToken = Stack.token(Role.ADMIN);
    LocalDate date = LocalDate.now(ZoneOffset.UTC);
    List<UUID> sources = byAvailable(9);
    List<String> paymentIds = new ArrayList<>();
    for (int index = 0; index < 6; index++) {
      Response created = createPayment("recon-" + index, sources.get(index), 100, "DEMO-OK", 9);
      expect(
          9,
          created.status() == 202,
          "reconciliation payment submission failed: " + created.body());
      paymentIds.add(created.json().get("id").asString());
    }
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      boolean allSettled = true;
      for (String id : paymentIds) {
        if (!getPayment(id, 9).get("state").asString().equals("SETTLED")) {
          allSettled = false;
        }
      }
      if (allSettled) {
        break;
      }
      sleep(200, 9);
    }
    Set<String> states = new HashSet<>();
    for (String id : paymentIds) {
      states.add(getPayment(id, 9).get("state").asString());
    }
    expect(
        9, states.equals(Set.of("SETTLED")), "not all reconciliation payments settled: " + states);

    Response statement =
        request("GET", "http://localhost:8083/statements/" + date, null, null, extbankHeaders());
    expect(9, statement.status() == 200, "statement fetch failed: " + statement.body());
    Seeded seeded = StatementGenerator.alter(statement.body(), 1, Seeds.seed());
    Response run =
        request(
            "POST",
            ledger("/v1/reconciliation/runs"),
            adminToken,
            json.writeValueAsString(
                Map.of("business_date", date.toString(), "statement_csv", seeded.csv())),
            Map.of());
    expect(
        9, run.status() == 201 || run.status() == 200, "reconciliation run failed: " + run.body());
    JsonNode result = run.json();
    expect(
        9, result.get("status").asString().equals("COMPLETED"), "reconciliation did not complete");
    Set<String> actualTypes = new HashSet<>();
    List<BreakOutput> breaks = new ArrayList<>();
    for (JsonNode item : result.get("breaks")) {
      String type = item.get("type").asString();
      actualTypes.add(type);
      breaks.add(new BreakOutput(type, item.get("reference").asString()));
    }
    expect(
        9,
        actualTypes.equals(new HashSet<>(BREAK_TYPES)),
        "unexpected reconciliation break types: " + actualTypes);
    expect(
        9,
        actualTypes.equals(new HashSet<>(seeded.expected().values())),
        "seeded break types differ from expected");
    breaks.sort(Comparator.comparing(BreakOutput::type));
    for (BreakOutput item : breaks) {
      say(9, "break " + item.type() + " reference=" + item.reference());
    }
  }

  private void step10() {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    String output = "";
    while (System.nanoTime() < deadline) {
      Result result =
          Exec.run(
              List.of(
                  "docker",
                  "compose",
                  "run",
                  "--rm",
                  "--no-deps",
                  "ledger",
                  "verify-audit",
                  "--require-sealed"),
              Map.of(),
              Duration.ofSeconds(30));
      output = result.output();
      if (result.exitCode() == 0) {
        String line =
            output
                .lines()
                .filter(value -> value.startsWith("audit chain ok:"))
                .findFirst()
                .orElse("");
        expect(10, !line.isBlank(), "audit verifier produced no success line: " + output.trim());
        say(10, line);
        return;
      }
      sleep(2_000, 10);
    }
    expect(10, false, "audit verification did not pass within 60 seconds: " + output.trim());
  }

  private void step11() {
    Result result =
        Exec.run(
            List.of(
                "docker",
                "compose",
                "run",
                "--rm",
                "--no-deps",
                "ledger",
                "check-invariants",
                "--topic-check"),
            Map.of(),
            Duration.ofSeconds(120));
    expect(11, result.exitCode() == 0, "invariant check failed: " + result.output().trim());
    String line =
        result
            .output()
            .lines()
            .filter(value -> value.equals("invariants ok: 0 violations"))
            .findFirst()
            .orElse("");
    expect(
        11, !line.isBlank(), "invariant checker omitted success line: " + result.output().trim());
    say(11, line);
  }

  private void step12() throws Exception {
    List<StrategyResult> results = new ArrayList<>();
    try {
      for (String strategy : STRATEGIES) {
        Result recreated =
            Exec.run(
                List.of(
                    "docker",
                    "compose",
                    "up",
                    "-d",
                    "--no-deps",
                    "--force-recreate",
                    "--wait",
                    "ledger"),
                Map.of("TALLY_LEDGER_STRATEGY", strategy),
                Duration.ofSeconds(180));
        expect(
            12,
            recreated.exitCode() == 0,
            "could not start ledger strategy " + strategy + ": " + recreated.output().trim());
        results.add(new StrategyResult(strategy, runLoad(Duration.ofSeconds(5), 12)));
      }
    } finally {
      Result restored =
          Exec.run(
              List.of(
                  "docker",
                  "compose",
                  "up",
                  "-d",
                  "--no-deps",
                  "--force-recreate",
                  "--wait",
                  "ledger"),
              Map.of("TALLY_LEDGER_STRATEGY", "pessimistic"),
              Duration.ofSeconds(180));
      expect(
          12,
          restored.exitCode() == 0,
          "could not restore pessimistic ledger: " + restored.output().trim());
    }
    say(12, "strategy  transfers_per_s  p50_ms  p99_ms  retries  exhausted");
    for (StrategyResult row : results) {
      LoadStats stats = row.stats();
      expect(12, stats.posted() > 0, "strategy " + row.strategy() + " posted no transfers");
      say(
          12,
          String.format(
              Locale.ROOT,
              "%-13s %15.1f %7.1f %7.1f %8d %10d",
              row.strategy(),
              stats.posted() / 5.0,
              stats.percentile(0.50),
              stats.percentile(0.99),
              stats.retries(),
              stats.exhausted()));
    }
  }

  private LoadRun startLoad(Duration duration, int step) {
    return new LoadRun(duration, step);
  }

  private LoadStats runLoad(Duration duration, int step) throws Exception {
    LoadRun load = new LoadRun(duration, step);
    try {
      long remaining = load.workloadDeadlineNanos() - System.nanoTime();
      if (remaining > 0) {
        TimeUnit.NANOSECONDS.sleep(remaining);
      }
      load.await(Duration.ofSeconds(60));
      expect(step, load.failure() == null, "load failed: " + load.failure());
      expect(step, load.unresolved() == 0, "load has unanswered operations: " + load.unresolved());
      return load.stats();
    } finally {
      load.close();
    }
  }

  private final class LoadRun implements AutoCloseable {

    private final LongAdder posted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder exhausted = new LongAdder();
    private final LongAdder unresolved = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final List<Double> latencies =
        java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<String> failure = new AtomicReference<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(32);
    private final List<Future<?>> workers = new ArrayList<>();
    private final long startedAt;
    private final long workloadDeadlineNanos;
    private final long answerDeadlineNanos;
    private final int step;

    private LoadRun(Duration duration, int step) {
      this.step = step;
      startedAt = System.nanoTime();
      workloadDeadlineNanos = startedAt + duration.toNanos();
      answerDeadlineNanos = workloadDeadlineNanos + Duration.ofSeconds(60).toNanos();
      CountDownLatch ready = new CountDownLatch(32);
      CountDownLatch start = new CountDownLatch(1);
      String token = operatorToken;
      for (int thread = 0; thread < 32; thread++) {
        int threadIndex = thread;
        workers.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  try {
                    start.await();
                    java.util.SplittableRandom random =
                        new java.util.SplittableRandom(runId.hashCode() * 31L + threadIndex);
                    while (System.nanoTime() < workloadDeadlineNanos) {
                      long operation = sequence.incrementAndGet();
                      int sourceIndex = hotSource(random, accounts.size());
                      int destinationIndex =
                          Workload.HOT.destination(random, accounts.size(), sourceIndex);
                      long amount = random.nextLong(1, 2_001);
                      String key = "demo-" + runId + "-load-" + operation;
                      Response response = null;
                      do {
                        long requestStart = System.nanoTime();
                        try {
                          response =
                              rawRequest(
                                  "POST",
                                  ledger("/v1/transfers"),
                                  token,
                                  json.writeValueAsString(
                                      Map.of(
                                          "source_account_id",
                                          accounts.get(sourceIndex),
                                          "destination_account_id",
                                          accounts.get(destinationIndex),
                                          "amount",
                                          amount,
                                          "currency",
                                          "EUR")),
                                  Map.of("Idempotency-Key", key));
                          latencies.add((System.nanoTime() - requestStart) / 1_000_000.0);
                          retries.add(Math.max(0, attempts(response) - 1));
                          if (response.status() == 201
                              || response.status() == 422
                              || retryExhausted(response)) {
                            count(response);
                            response = null;
                            break;
                          }
                          if (response.status() < 500 || response.status() >= 600) {
                            failure.compareAndSet(
                                null,
                                "unexpected transfer status "
                                    + response.status()
                                    + ": "
                                    + response.body());
                            break;
                          }
                        } catch (IOException e) {
                          // Retry this logical operation with its original key.
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          failure.compareAndSet(null, "load request interrupted");
                          break;
                        }
                        if (System.nanoTime() >= answerDeadlineNanos) {
                          unresolved.increment();
                          response = null;
                          break;
                        }
                        TimeUnit.MILLISECONDS.sleep(100);
                      } while (true);
                      if (response != null) {
                        unresolved.increment();
                      }
                      if (failure.get() != null) {
                        break;
                      }
                    }
                  } catch (Exception e) {
                    failure.compareAndSet(null, message(e));
                    unresolved.increment();
                  }
                }));
      }
      try {
        ready.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        failure.set("load startup interrupted");
      }
      start.countDown();
    }

    private int hotSource(java.util.random.RandomGenerator random, int poolSize) {
      return random.nextDouble() < 0.8 ? random.nextInt(2) : random.nextInt(poolSize);
    }

    private void count(Response response) {
      if (response.status() == 201) {
        posted.increment();
      } else if (response.status() == 422) {
        rejected.increment();
      } else if (retryExhausted(response)) {
        exhausted.increment();
      }
    }

    private boolean retryExhausted(Response response) {
      if (response.status() != 503) {
        return false;
      }
      try {
        return response.json().get("error").get("code").asString().equals("RETRY_EXHAUSTED");
      } catch (RuntimeException e) {
        return false;
      }
    }

    private String counts() {
      return "posted="
          + posted.sum()
          + " rejected="
          + rejected.sum()
          + " exhausted="
          + exhausted.sum();
    }

    private long posted() {
      return posted.sum();
    }

    private long unresolved() {
      return unresolved.sum();
    }

    private String failure() {
      return failure.get();
    }

    private long workloadDeadlineNanos() {
      return workloadDeadlineNanos;
    }

    private void await(Duration timeout) throws InterruptedException {
      executor.shutdown();
      if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        executor.shutdownNow();
        expect(step, false, "load workers did not finish before timeout");
      }
    }

    private LoadStats stats() {
      List<Double> sorted;
      synchronized (latencies) {
        sorted = new ArrayList<>(latencies);
      }
      sorted.sort(Double::compareTo);
      return new LoadStats(posted(), rejected.sum(), exhausted.sum(), retries.sum(), sorted);
    }

    @Override
    public void close() {
      executor.shutdownNow();
    }
  }

  /** The demo accounts, most available money first; the hot load drains its source accounts. */
  private List<UUID> byAvailable(int step) {
    Map<UUID, Long> available = new HashMap<>();
    for (UUID account : accounts) {
      Response balance =
          request(
              "GET", ledger("/v1/accounts/" + account + "/balance"), operatorToken, null, Map.of());
      expect(step, balance.status() == 200, "balance lookup failed: " + balance.body());
      available.put(account, balance.json().get("available").asLong());
    }
    List<UUID> sorted = new ArrayList<>(accounts);
    sorted.sort(Comparator.comparing(available::get, Comparator.reverseOrder()));
    return sorted;
  }

  private void createExtbankRule(String creditor, String behaviour, int delay, int step) {
    Map<String, Object> body = new HashMap<>();
    body.put("creditor_account", creditor);
    body.put("behaviour", behaviour);
    if (delay > 0) {
      body.put("delay_ms", delay);
    }
    Response response =
        request(
            "POST",
            "http://localhost:8083/control/rules",
            null,
            json.writeValueAsString(body),
            extbankHeaders());
    expect(step, response.status() == 201, "extbank rule creation failed: " + response.body());
  }

  private Response createPayment(
      String suffix, UUID source, long amount, String creditor, int step) {
    Response response =
        request(
            "POST",
            ledger("/v1/external-payments"),
            operatorToken,
            json.writeValueAsString(
                Map.of(
                    "source_account_id", source,
                    "amount", amount,
                    "currency", "EUR",
                    "creditor_account", creditor)),
            Map.of("Idempotency-Key", "demo-" + runId + "-payment-" + suffix));
    expect(step, response.status() == 202, "payment creation failed: " + response.body());
    return response;
  }

  private JsonNode pollPayment(String id, Duration timeout, int step, long intervalMs) {
    long deadline = System.nanoTime() + timeout.toNanos();
    JsonNode payment = null;
    while (System.nanoTime() < deadline) {
      payment = getPayment(id, step);
      String state = payment.get("state").asString();
      if (Set.of("SETTLED", "REJECTED_COMPENSATED", "MANUAL_REVIEW", "FAILED").contains(state)) {
        return payment;
      }
      sleep(intervalMs, step);
    }
    expect(step, false, "payment " + id + " did not reach a terminal state");
    return payment;
  }

  private JsonNode getPayment(String id, int step) {
    Response response =
        request("GET", ledger("/v1/external-payments/" + id), operatorToken, null, Map.of());
    expect(step, response.status() == 200, "payment lookup failed: " + response.body());
    return response.json();
  }

  private void sleep(long millis, int step) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DemoFailure(step, "interrupted");
    }
  }

  private Response transfer(String key, UUID source, UUID destination, long amount) {
    return request(
        "POST",
        ledger("/v1/transfers"),
        operatorToken,
        json.writeValueAsString(
            Map.of(
                "source_account_id", source,
                "destination_account_id", destination,
                "amount", amount,
                "currency", "EUR")),
        Map.of("Idempotency-Key", "demo-" + runId + "-" + key));
  }

  private List<JsonNode> allEntries(UUID account) {
    List<JsonNode> entries = new ArrayList<>();
    String path = ledger("/v1/accounts/" + account + "/entries?limit=200");
    String page = path;
    while (true) {
      Response response = request("GET", page, operatorToken, null, Map.of());
      expect(5, response.status() == 200, "entry page failed: " + response.body());
      JsonNode pageJson = response.json();
      pageJson.get("items").forEach(entries::add);
      JsonNode cursor = pageJson.get("next_cursor");
      if (cursor.isNull()) {
        return entries;
      }
      page = path + "&cursor=" + URLEncoder.encode(cursor.asString(), StandardCharsets.UTF_8);
    }
  }

  private Response request(
      String method, String url, String token, String body, Map<String, String> headers) {
    try {
      return rawRequest(method, url, token, body, headers);
    } catch (IOException e) {
      throw new DemoFailure(activeStep.get(), "HTTP request failed: " + message(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DemoFailure(activeStep.get(), "HTTP request interrupted");
    }
  }

  private Response rawRequest(
      String method, String url, String token, String body, Map<String, String> headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT);
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    headers.forEach(builder::header);
    if (body != null) {
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofString(body));
    } else {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return Http.send(builder, REQUEST_TIMEOUT);
  }

  private Map<String, String> extbankHeaders() {
    String key = Stack.env("EXTBANK_API_KEY");
    expect(activeStep.get(), key != null && !key.isBlank(), "EXTBANK_API_KEY is not configured");
    return Map.of("X-Api-Key", key);
  }

  private String ledger(String path) {
    return "http://localhost:8080" + path;
  }

  private UUID uuid(JsonNode node, int step, String description) {
    try {
      return UUID.fromString(node.asString());
    } catch (RuntimeException e) {
      throw new DemoFailure(step, "invalid " + description + ": " + node);
    }
  }

  private int attempts(Response response) {
    return response.headers().entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase("X-Tally-Attempts"))
        .flatMap(entry -> entry.getValue().stream())
        .findFirst()
        .map(
            value -> {
              try {
                return Integer.parseInt(value);
              } catch (NumberFormatException e) {
                return 1;
              }
            })
        .orElse(1);
  }

  private String message(Exception exception) {
    return exception.getMessage() == null
        ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }

  @FunctionalInterface
  private interface StepAction {
    void run() throws Exception;
  }

  private record Result(int exitCode, String output) {}

  private record BreakOutput(String type, String reference) {}

  private record StrategyResult(String strategy, LoadStats stats) {}

  private record LoadStats(
      long posted, long rejected, long exhausted, long retries, List<Double> latencies) {

    private double percentile(double percentile) {
      if (latencies.isEmpty()) {
        return 0.0;
      }
      int rank = (int) Math.ceil(percentile * latencies.size());
      return latencies.get(Math.max(0, rank - 1));
    }
  }

  private static final class Exec {

    private Exec() {}

    private static Result run(List<String> command, Map<String, String> env, Duration timeout) {
      ProcessBuilder builder =
          new ProcessBuilder(command)
              .directory(Path.of(System.getProperty("proof.root")).toFile())
              .redirectErrorStream(true);
      builder.environment().putAll(env);
      try {
        Process process = builder.start();
        CompletableFuture<byte[]> output =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return process.getInputStream().readAllBytes();
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
          process.destroyForcibly();
          process.waitFor();
        }
        String text = new String(output.join(), StandardCharsets.UTF_8);
        return new Result(finished ? process.exitValue() : -1, text);
      } catch (IOException e) {
        throw new IllegalStateException("command failed: " + command, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("command interrupted: " + command, e);
      }
    }
  }

  private static final class DemoFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final int step;

    private DemoFailure(int step, String reason) {
      super(reason);
      this.step = step;
    }
  }
}
