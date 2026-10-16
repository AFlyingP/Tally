package tally.proof.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import tally.platform.security.Role;
import tally.proof.harness.Http;
import tally.proof.harness.LedgerClient;
import tally.proof.harness.Sizes;
import tools.jackson.databind.json.JsonMapper;

public final class PaymentScenarios {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
  private static final long PAYMENT_AMOUNT = 1_000;
  // UNION counts each row once even when several independent links identify it.
  private static final String PAYMENT_COUNTS =
      """
      WITH payments AS (
        SELECT * FROM ledger.external_payment WHERE id = ANY(?)
      ), payment_audits AS (
        SELECT p.id AS payment_id, a.id, a.action, a.transaction_id
        FROM payments p JOIN ledger.audit_log a
          ON a.resource_type = 'external_payment' AND a.resource_id = p.id::text
        UNION
        SELECT p.id, a.id, a.action, a.transaction_id
        FROM payments p JOIN ledger.audit_log a ON a.request_id = p.request_id
        UNION
        SELECT p.id, a.id, a.action, a.transaction_id
        FROM payments p JOIN ledger.payment_event e ON e.payment_id = p.id
        JOIN ledger.audit_log a ON a.id = e.audit_id
      ), payment_holds AS (
        SELECT p.id AS payment_id, h.id
        FROM payments p JOIN ledger.hold h ON h.id = p.hold_id
        UNION
        SELECT p.id, h.id
        FROM payments p JOIN ledger.idempotency_key k
          ON k.client_id = 'job:orchestrator' AND k.key = 'pay:' || p.id::text || ':hold'
        JOIN ledger.hold h ON h.idempotency_key_id = k.id
        UNION
        SELECT a.payment_id, e.hold_id
        FROM payment_audits a JOIN ledger.hold_event e ON e.audit_id = a.id
        WHERE a.action = 'payment.hold' AND e.type = 'PLACED'
      ), payment_transactions AS (
        SELECT p.id AS payment_id, t.id, t.kind
        FROM payments p JOIN ledger.ledger_transaction t
          ON t.id IN (p.capture_transaction_id, p.resolution_transaction_id)
        UNION
        SELECT p.id, t.id, t.kind
        FROM payments p JOIN ledger.ledger_transaction t ON t.request_id = p.request_id
        UNION
        SELECT p.id, t.id, t.kind
        FROM payments p JOIN ledger.idempotency_key k
          ON k.client_id = 'job:orchestrator' AND k.key LIKE 'pay:' || p.id::text || ':%'
        JOIN ledger.ledger_transaction t ON t.idempotency_key_id = k.id
        UNION
        SELECT a.payment_id, t.id, t.kind
        FROM payment_audits a JOIN ledger.ledger_transaction t ON t.id = a.transaction_id
        UNION
        SELECT ph.payment_id, t.id, t.kind
        FROM payment_holds ph JOIN ledger.hold h ON h.id = ph.id
        JOIN ledger.ledger_transaction t ON t.id = h.capture_transaction_id
        UNION
        SELECT ph.payment_id, t.id, t.kind
        FROM payment_holds ph JOIN ledger.hold_event e ON e.hold_id = ph.id
        JOIN ledger.ledger_transaction t ON t.id = e.transaction_id
      )
      SELECT p.id,
        (SELECT count(*) FROM payment_holds h WHERE h.payment_id = p.id) AS holds,
        count(t.id) FILTER (WHERE t.kind = 'HOLD_CAPTURE') AS captures,
        count(t.id) FILTER (WHERE t.kind = 'EXTERNAL_SETTLEMENT') AS settlements,
        count(t.id) FILTER (WHERE t.kind = 'EXTERNAL_COMPENSATION') AS compensations,
        count(t.id) FILTER (WHERE t.kind NOT IN
          ('HOLD_CAPTURE', 'EXTERNAL_SETTLEMENT', 'EXTERNAL_COMPENSATION')) AS unexpected
      FROM payments p LEFT JOIN payment_transactions t ON t.payment_id = p.id
      GROUP BY p.id
      """;

  private PaymentScenarios() {}

  public static FaultResult schedulerKill(
      FaultStack stack, String strategy, Sizes sizes, long seed) {
    LoadDriver driver = driver(stack, sizes, seed);
    LedgerClient client = client(stack);
    String runId = driver.runId();
    try {
      driver.setup();
      driver.start();
      UUID source = createAccount(client, runId + "-schedule-source");
      UUID destination = createAccount(client, runId + "-schedule-destination");
      assertThat(
              setupRequest(
                      stack,
                      "/v1/deposits",
                      JSON.writeValueAsString(
                          Map.of(
                              "account_id",
                              source,
                              "amount",
                              sizes.schedulerOccurrences() * 100L,
                              "currency",
                              "EUR")),
                      runId + "-schedule-fund")
                  .status())
          .as("schedule source funding")
          .isEqualTo(201);
      assertThat(
              setupRequest(
                      stack,
                      "/v1/deposits",
                      JSON.writeValueAsString(
                          Map.of("account_id", destination, "amount", 100, "currency", "EUR")),
                      runId + "-schedule-destination-fund")
                  .status())
          .as("schedule destination funding")
          .isEqualTo(201);
      Instant due = Instant.now().plusSeconds(15).truncatedTo(ChronoUnit.MICROS);
      for (int index = 0; index < sizes.schedulerOccurrences(); index++) {
        String description = runId + "-sched-" + index;
        Http.Response response =
            setupRequest(
                stack,
                "/v1/schedules",
                JSON.writeValueAsString(
                    Map.of(
                        "source_account_id", source,
                        "destination_account_id", destination,
                        "amount", 100,
                        "currency", "EUR",
                        "frequency", "ONCE",
                        "start_at", due,
                        "description", description)),
                runId + "-schedule-" + index);
        assertThat(response.status()).as("schedule creation: %s", response.body()).isEqualTo(201);
      }

      stack.withEnv("worker", "TALLY_LEDGER_ROLES", "scheduler");
      stack.withEnv("worker2", "TALLY_LEDGER_ROLES", "scheduler");
      stack.withEnv("worker", "TALLY_FAILPOINT", "scheduler.before_commit");
      stack.start("worker");
      stack.start("worker2");
      stack.awaitExit("worker", Duration.ofSeconds(60));
      await()
          .pollDelay(Duration.ZERO)
          .pollInterval(POLL_INTERVAL)
          .atMost(Duration.ofSeconds(90))
          .until(() -> postedSchedules(stack, runId) >= 10);
      stack.kill("worker2");
      stack.withEnv("worker", "TALLY_FAILPOINT", "");
      stack.start("worker");
      stack.start("worker2");
      await()
          .pollDelay(Duration.ZERO)
          .pollInterval(POLL_INTERVAL)
          .atMost(Duration.ofSeconds(90))
          .until(() -> postedSchedules(stack, runId) == sizes.schedulerOccurrences());
      driver.awaitEnd();
      driver.resolveUnresolved(Duration.ofSeconds(60));

      int duplicates;
      int transactionCount;
      try {
        duplicates = duplicateScheduled(stack, runId);
        transactionCount = scheduledTransactions(stack, runId);
      } catch (SQLException e) {
        throw new IllegalStateException("failed to check scheduled transactions", e);
      }
      assertThat(duplicates).as("duplicate scheduled transactions").isZero();
      assertThat(transactionCount)
          .as("scheduled transaction count")
          .isEqualTo(sizes.schedulerOccurrences());
      assertThat(driver.failures()).as("scheduler load driver failures").isEmpty();
      FaultResult result = FaultResult.collect(Scenario.SCHEDULER_KILL, strategy, stack, driver);
      return withMeasurements(result, duplicates, null, null);
    } finally {
      stopWorkers(stack);
    }
  }

  public static FaultResult relayKill(FaultStack stack, String strategy, Sizes sizes, long seed) {
    LoadDriver driver = driver(stack, sizes, seed);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      driver.setup();
      stack.withEnv("worker", "TALLY_LEDGER_ROLES", "relay");
      stack.withEnv("worker", "TALLY_FAILPOINT", "relay.after_send");
      stack.start("worker");
      long loadStarted = System.nanoTime();
      driver.start();
      stack.awaitExit("worker", Duration.ofSeconds(60));
      stack.withEnv("worker", "TALLY_FAILPOINT", "");
      stack.start("worker");
      long delayMillis =
          Duration.ofSeconds(sizes.faultSeconds()).toMillis() * 60 / 100
              - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loadStarted);
      var secondKill =
          scheduler.schedule(
              () -> {
                stack.kill("worker");
                stack.start("worker");
              },
              delayMillis,
              TimeUnit.MILLISECONDS);
      driver.awaitEnd();
      secondKill.get();
      driver.resolveUnresolved(Duration.ofSeconds(60));
      awaitOutboxDrained(stack, Duration.ofSeconds(90));
      EventLog.Counts events = readEvents(stack);
      assertThat(events.missing()).as("missing published events").isZero();
      assertThat(stack.checker(true, true)).as("relay invariants").isEmpty();
      assertThat(driver.failures()).as("relay load driver failures").isEmpty();
      FaultResult result = FaultResult.collect(Scenario.RELAY_KILL, strategy, stack, driver);
      return withMeasurements(result, null, events.missing(), events.duplicates());
    } catch (Exception e) {
      throw failure("relay scenario failed", e);
    } finally {
      scheduler.shutdownNow();
      scheduler.close();
      stopWorkers(stack);
    }
  }

  public static FaultResult brokerRestart(
      FaultStack stack, String strategy, Sizes sizes, long seed) {
    LoadDriver driver = driver(stack, sizes, seed);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      driver.setup();
      stack.withEnv("worker", "TALLY_LEDGER_ROLES", "relay");
      stack.withEnv("worker", "TALLY_FAILPOINT", "");
      stack.start("worker");
      driver.start();
      long delayMillis = Duration.ofSeconds(sizes.faultSeconds()).toMillis() * 40 / 100;
      var restart =
          scheduler.schedule(() -> stack.restart("kafka", 10), delayMillis, TimeUnit.MILLISECONDS);
      driver.awaitEnd();
      restart.get();
      driver.resolveUnresolved(Duration.ofSeconds(60));
      awaitOutboxDrained(stack, Duration.ofSeconds(120));
      EventLog.Counts events = readEvents(stack);
      assertThat(events.missing()).as("missing published events").isZero();
      assertThat(stack.checker(true, true)).as("broker restart invariants").isEmpty();
      assertThat(driver.failures()).as("broker restart load driver failures").isEmpty();
      FaultResult result = FaultResult.collect(Scenario.BROKER_RESTART, strategy, stack, driver);
      return withMeasurements(result, null, events.missing(), events.duplicates());
    } catch (Exception e) {
      throw failure("broker restart scenario failed", e);
    } finally {
      scheduler.shutdownNow();
      scheduler.close();
      stopWorkers(stack);
    }
  }

  public static FaultResult extbankFaults(
      FaultStack stack, String strategy, Sizes sizes, long seed) {
    LoadDriver driver = driver(stack, sizes, seed);
    LedgerClient client = client(stack);
    String runId = driver.runId();
    int paymentCount = sizes.faultPayments();
    int sourceCount = 20;
    long sourceFunding = ((paymentCount + sourceCount - 1L) / sourceCount) * PAYMENT_AMOUNT;
    List<UUID> sources = new ArrayList<>();
    List<UUID> paymentIds = new ArrayList<>();
    List<String> creditors = new ArrayList<>();
    try {
      long initialClearingBalance = clearingBalance(stack);
      clearExtbankRules(stack);
      driver.setup();
      for (int index = 0; index < sourceCount; index++) {
        UUID source = createAccount(client, runId + "-fault-source-" + index);
        assertThat(
                setupRequest(
                        stack,
                        "/v1/deposits",
                        JSON.writeValueAsString(
                            Map.of(
                                "account_id", source, "amount", sourceFunding, "currency", "EUR")),
                        runId + "-fault-fund-" + index)
                    .status())
            .as("payment source funding")
            .isEqualTo(201);
        sources.add(source);
      }
      for (int index = 0; index < 30; index++) {
        creditors.add(String.format("CRED-%04d", index));
      }
      for (int index = 0; index < 10; index++) {
        addExtbankRule(stack, creditors.get(index), "DROP_RESPONSE", 400, null);
      }
      for (int index = 10; index < 20; index++) {
        addExtbankRule(stack, creditors.get(index), "SERVER_ERROR", 0, 2);
      }

      stack.withEnv("worker", "TALLY_LEDGER_ROLES", "orchestrator,expiry");
      stack.withEnv("worker", "TALLY_FAILPOINT", "");
      stack.start("worker");
      driver.start();
      for (int index = 0; index < paymentCount; index++) {
        Http.Response response =
            setupRequest(
                stack,
                "/v1/external-payments",
                JSON.writeValueAsString(
                    Map.of(
                        "source_account_id",
                        sources.get(index % sources.size()),
                        "amount",
                        PAYMENT_AMOUNT,
                        "currency",
                        "EUR",
                        "creditor_account",
                        creditors.get(index % creditors.size()))),
                runId + "-fault-payment-" + index);
        assertThat(response.status()).as("payment creation: %s", response.body()).isEqualTo(202);
        paymentIds.add(UUID.fromString(response.json().get("id").asString()));
      }
      awaitPaymentsTerminal(stack, paymentIds, Duration.ofSeconds(120));
      driver.awaitEnd();
      driver.resolveUnresolved(Duration.ofSeconds(60));
      assertThat(driver.failures()).as("external payment load driver failures").isEmpty();

      Map<String, Integer> states = paymentStates(stack, paymentIds);
      assertThat(states.keySet()).isSubsetOf("SETTLED", "MANUAL_REVIEW");
      assertThat(states.values().stream().mapToInt(Integer::intValue).sum())
          .isEqualTo(paymentCount);
      assertNoDuplicatePaymentTransactions(stack, paymentIds);
      long createdOrLost =
          paymentConservationDifference(
              stack,
              driver.accounts(),
              sources,
              paymentIds,
              driver.deposited() + sourceFunding * sourceCount,
              initialClearingBalance);
      assertThat(createdOrLost).as("created or lost money").isZero();
      assertThat(stack.checker(false, false)).as("external payment invariants").isEmpty();
      FaultResult result = FaultResult.collect(Scenario.EXTBANK_FAULTS, strategy, stack, driver);
      return withPaymentMeasurements(result, states, createdOrLost);
    } catch (Exception e) {
      throw failure("external payment faults scenario failed", e);
    } finally {
      stopWorkers(stack);
      clearExtbankRules(stack);
    }
  }

  public static FaultResult orchestratorKill(
      FaultStack stack, String strategy, Sizes sizes, long seed) {
    LoadDriver driver = driver(stack, sizes, seed);
    LedgerClient client = client(stack);
    String runId = driver.runId();
    int paymentCount = sizes.killPayments();
    int sourceCount = 20;
    long sourceFunding = ((paymentCount + sourceCount - 1L) / sourceCount) * PAYMENT_AMOUNT * 3;
    List<UUID> sources = new ArrayList<>();
    List<UUID> paymentIds = new ArrayList<>();
    try {
      clearExtbankRules(stack);
      driver.setup();
      for (int index = 0; index < sourceCount; index++) {
        UUID source = createAccount(client, runId + "-kill-source-" + index);
        assertThat(
                setupRequest(
                        stack,
                        "/v1/deposits",
                        JSON.writeValueAsString(
                            Map.of(
                                "account_id", source, "amount", sourceFunding, "currency", "EUR")),
                        runId + "-kill-fund-" + index)
                    .status())
            .as("payment source funding")
            .isEqualTo(201);
        sources.add(source);
      }
      stack.withEnv("worker", "TALLY_LEDGER_ROLES", "orchestrator,expiry");
      driver.start();
      List<String> failpoints =
          List.of("payment.after_hold", "payment.after_capture", "payment.after_submit");
      for (int subrun = 0; subrun < failpoints.size(); subrun++) {
        if (stack.running("worker")) {
          stack.kill("worker");
        }
        stack.withEnv("worker", "TALLY_FAILPOINT", failpoints.get(subrun));
        stack.start("worker");
        List<UUID> subrunPaymentIds = new ArrayList<>();
        for (int index = 0; index < paymentCount; index++) {
          Http.Response response =
              setupRequest(
                  stack,
                  "/v1/external-payments",
                  JSON.writeValueAsString(
                      Map.of(
                          "source_account_id",
                          sources.get(index % sources.size()),
                          "amount",
                          PAYMENT_AMOUNT,
                          "currency",
                          "EUR",
                          "creditor_account",
                          String.format("KILL-%04d-%d", index, subrun))),
                  runId + "-kill-" + subrun + "-payment-" + index);
          assertThat(response.status()).as("payment creation: %s", response.body()).isEqualTo(202);
          UUID id = UUID.fromString(response.json().get("id").asString());
          paymentIds.add(id);
          subrunPaymentIds.add(id);
        }
        stack.awaitExit("worker", Duration.ofSeconds(60));
        stack.withEnv("worker", "TALLY_FAILPOINT", "");
        stack.start("worker");
        awaitPaymentsState(stack, subrunPaymentIds, "SETTLED", Duration.ofSeconds(120));
      }
      driver.awaitEnd();
      driver.resolveUnresolved(Duration.ofSeconds(60));
      assertThat(driver.failures()).as("orchestrator kill load driver failures").isEmpty();
      Map<String, Integer> states = paymentStates(stack, paymentIds);
      assertThat(states).containsExactly(Map.entry("SETTLED", paymentIds.size()));
      assertEachPaymentExactlyOnce(stack, paymentIds);
      assertBankConfirmations(stack, paymentIds);
      assertThat(stack.checker(false, false)).as("orchestrator kill invariants").isEmpty();
      FaultResult result = FaultResult.collect(Scenario.ORCHESTRATOR_KILL, strategy, stack, driver);
      return withPaymentMeasurements(result, states, null);
    } catch (Exception e) {
      throw failure("orchestrator kill scenario failed", e);
    } finally {
      stopWorkers(stack);
      clearExtbankRules(stack);
    }
  }

  private static LoadDriver driver(FaultStack stack, Sizes sizes, long seed) {
    return new LoadDriver(
        stack,
        UUID.randomUUID().toString(),
        sizes.faultThreads(),
        Duration.ofSeconds(sizes.faultSeconds()),
        Duration.ofMillis(2000),
        seed);
  }

  private static LedgerClient client(FaultStack stack) {
    return new LedgerClient(stack.ledgerUrl(), stack.issuer().token(Role.ADMIN));
  }

  private static Http.Response setupRequest(
      FaultStack stack, String path, String body, String idempotencyKey) {
    String url = stack.ledgerUrl() + path;
    String token = stack.issuer().token(Role.ADMIN);
    Http.Response response = null;
    for (int attempt = 0; attempt < 30; attempt++) {
      response = Http.post(url, token, body, "Idempotency-Key", idempotencyKey);
      if (response.status() != 503 || attempt == 29) {
        return response;
      }
      try {
        TimeUnit.SECONDS.sleep(1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("setup request retry interrupted", e);
      }
    }
    throw new IllegalStateException("unreachable setup request retry state");
  }

  private static UUID createAccount(LedgerClient client, String key) {
    Http.Response response = client.createAccount(0, true);
    assertThat(response.status()).as("account creation: %s", response.body()).isEqualTo(201);
    return UUID.fromString(response.json().get("id").asString());
  }

  private static int postedSchedules(FaultStack stack, String runId) throws SQLException {
    try (Connection connection = stack.jdbc();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT count(*) FROM ledger.schedule_occurrence o"
                    + " JOIN ledger.schedule s ON s.id = o.schedule_id"
                    + " WHERE s.description LIKE ? AND o.state = 'POSTED'")) {
      query.setString(1, runId + "-sched-%");
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static int scheduledTransactions(FaultStack stack, String runId) throws SQLException {
    try (Connection connection = stack.jdbc();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT count(*) FROM ledger.ledger_transaction" + " WHERE description LIKE ?")) {
      query.setString(1, runId + "-sched-%");
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static int duplicateScheduled(FaultStack stack, String runId) throws SQLException {
    try (Connection connection = stack.jdbc();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT count(*) FROM (SELECT description FROM ledger.ledger_transaction"
                    + " WHERE description LIKE ? GROUP BY description HAVING count(*) > 1) d")) {
      query.setString(1, runId + "-sched-%");
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static void awaitOutboxDrained(FaultStack stack, Duration timeout) {
    await()
        .pollDelay(Duration.ZERO)
        .pollInterval(POLL_INTERVAL)
        .atMost(timeout)
        .until(
            () -> {
              try (Connection connection = stack.jdbc();
                  PreparedStatement query =
                      connection.prepareStatement(
                          "SELECT count(*) FROM ledger.outbox WHERE published_at IS NULL");
                  ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1) == 0;
              } catch (SQLException e) {
                throw new IllegalStateException("failed to check outbox drain", e);
              }
            });
  }

  private static EventLog.Counts readEvents(FaultStack stack) throws SQLException {
    Set<UUID> expected = new HashSet<>();
    try (Connection connection = stack.jdbc();
        PreparedStatement query =
            connection.prepareStatement("SELECT event_id FROM ledger.outbox");
        ResultSet rows = query.executeQuery()) {
      while (rows.next()) {
        expected.add(rows.getObject(1, UUID.class));
      }
    }
    return EventLog.read(stack.kafkaBootstrap(), "tally.transactions.v1", expected);
  }

  private static void clearExtbankRules(FaultStack stack) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(stack.extbankUrl() + "/control/rules"))
            .timeout(Duration.ofSeconds(5))
            .header("X-Api-Key", stack.extbankApiKey())
            .DELETE()
            .build();
    try {
      HttpResponse<Void> response =
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
      assertThat(response.statusCode()).as("clear external bank rules").isEqualTo(204);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("failed to clear external bank rules", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("clearing external bank rules interrupted", e);
    }
  }

  private static void addExtbankRule(
      FaultStack stack, String creditor, String behaviour, int delayMillis, Integer remaining) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("creditor_account", creditor);
    body.put("behaviour", behaviour);
    body.put("delay_ms", delayMillis);
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
    assertThat(response.status())
        .as("create external bank rule: %s", response.body())
        .isEqualTo(201);
  }

  private static void awaitPaymentsTerminal(
      FaultStack stack, List<UUID> paymentIds, Duration timeout) {
    await()
        .pollDelay(Duration.ZERO)
        .pollInterval(POLL_INTERVAL)
        .atMost(timeout)
        .until(() -> terminalPaymentCount(stack, paymentIds) == paymentIds.size());
  }

  private static void awaitPaymentsState(
      FaultStack stack, List<UUID> paymentIds, String state, Duration timeout) {
    await()
        .pollDelay(Duration.ZERO)
        .pollInterval(POLL_INTERVAL)
        .atMost(timeout)
        .until(() -> paymentStateCount(stack, paymentIds, state) == paymentIds.size());
  }

  private static int terminalPaymentCount(FaultStack stack, List<UUID> paymentIds) {
    return paymentCount(
        stack, paymentIds, "state IN ('SETTLED','REJECTED_COMPENSATED','MANUAL_REVIEW','FAILED')");
  }

  private static int paymentStateCount(FaultStack stack, List<UUID> paymentIds, String state) {
    return paymentCount(stack, paymentIds, "state = '" + state + "'");
  }

  private static int paymentCount(FaultStack stack, List<UUID> paymentIds, String predicate) {
    try (Connection connection = stack.jdbc();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT count(*) FROM ledger.external_payment WHERE id = ANY(?) AND "
                    + predicate)) {
      Array ids = connection.createArrayOf("uuid", paymentIds.toArray(UUID[]::new));
      try {
        query.setArray(1, ids);
        try (ResultSet rows = query.executeQuery()) {
          rows.next();
          return rows.getInt(1);
        }
      } finally {
        ids.free();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to read external payment state", e);
    }
  }

  private static Map<String, Integer> paymentStates(FaultStack stack, List<UUID> paymentIds) {
    try (Connection connection = stack.jdbc();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT state, count(*) FROM ledger.external_payment WHERE id = ANY(?)"
                    + " GROUP BY state ORDER BY state")) {
      Array ids = connection.createArrayOf("uuid", paymentIds.toArray(UUID[]::new));
      try {
        query.setArray(1, ids);
        Map<String, Integer> states = new java.util.LinkedHashMap<>();
        try (ResultSet rows = query.executeQuery()) {
          while (rows.next()) {
            states.put(rows.getString(1), rows.getInt(2));
          }
        }
        return Map.copyOf(states);
      } finally {
        ids.free();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to read external payment states", e);
    }
  }

  private static void assertNoDuplicatePaymentTransactions(FaultStack stack, List<UUID> paymentIds)
      throws SQLException {
    try (Connection connection = stack.jdbc();
        PreparedStatement query = connection.prepareStatement(PAYMENT_COUNTS)) {
      Array ids = connection.createArrayOf("uuid", paymentIds.toArray(UUID[]::new));
      try {
        query.setArray(1, ids);
        int checked = 0;
        try (ResultSet rows = query.executeQuery()) {
          while (rows.next()) {
            UUID paymentId = rows.getObject("id", UUID.class);
            for (String kind : List.of("captures", "settlements", "compensations")) {
              assertThat(rows.getLong(kind))
                  .as("%s for payment %s", kind, paymentId)
                  .isLessThanOrEqualTo(1);
            }
            assertThat(rows.getLong("settlements") + rows.getLong("compensations"))
                .as("settlement or compensation count for payment %s", paymentId)
                .isLessThanOrEqualTo(1);
            assertThat(rows.getLong("unexpected"))
                .as("unexpected transaction kinds for payment %s", paymentId)
                .isZero();
            checked++;
          }
        }
        assertThat(checked)
            .as("payments checked for duplicate transactions")
            .isEqualTo(paymentIds.size());
      } finally {
        ids.free();
      }
    }
  }

  private static long clearingBalance(FaultStack stack) throws SQLException {
    try (Connection connection = stack.jdbc();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT coalesce(sum(balance), 0) FROM ledger.account"
                    + " WHERE kind = 'CLEARING' AND currency = 'EUR'");
        ResultSet rows = query.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static long paymentConservationDifference(
      FaultStack stack,
      List<UUID> loadAccounts,
      List<UUID> sources,
      List<UUID> paymentIds,
      long deposits,
      long initialClearingBalance)
      throws SQLException {
    Set<UUID> accountIds = new HashSet<>(loadAccounts);
    accountIds.addAll(sources);
    try (Connection connection = stack.jdbc();
        PreparedStatement customers =
            connection.prepareStatement(
                "SELECT coalesce(sum(balance), 0) FROM ledger.account WHERE id = ANY(?)");
        PreparedStatement clearing =
            connection.prepareStatement(
                "SELECT coalesce(sum(balance), 0) FROM ledger.account"
                    + " WHERE kind = 'CLEARING' AND currency = 'EUR'");
        PreparedStatement settled =
            connection.prepareStatement(
                "SELECT coalesce(sum(amount), 0) FROM ledger.external_payment"
                    + " WHERE id = ANY(?) AND state = 'SETTLED'")) {
      Array accountArray = connection.createArrayOf("uuid", accountIds.toArray(UUID[]::new));
      Array paymentArray = connection.createArrayOf("uuid", paymentIds.toArray(UUID[]::new));
      try {
        customers.setArray(1, accountArray);
        settled.setArray(1, paymentArray);
        long customerBalance;
        long clearingBalance;
        long settledAmount;
        try (ResultSet rows = customers.executeQuery()) {
          rows.next();
          customerBalance = rows.getLong(1);
        }
        try (ResultSet rows = clearing.executeQuery()) {
          rows.next();
          clearingBalance = rows.getLong(1);
        }
        try (ResultSet rows = settled.executeQuery()) {
          rows.next();
          settledAmount = rows.getLong(1);
        }
        long clearingChange = clearingBalance - initialClearingBalance;
        return Math.abs(deposits - (customerBalance + clearingChange + settledAmount));
      } finally {
        accountArray.free();
        paymentArray.free();
      }
    }
  }

  private static void assertEachPaymentExactlyOnce(FaultStack stack, List<UUID> paymentIds)
      throws SQLException {
    try (Connection connection = stack.jdbc();
        PreparedStatement query = connection.prepareStatement(PAYMENT_COUNTS)) {
      Array ids = connection.createArrayOf("uuid", paymentIds.toArray(UUID[]::new));
      try {
        query.setArray(1, ids);
        int checked = 0;
        try (ResultSet rows = query.executeQuery()) {
          while (rows.next()) {
            UUID paymentId = rows.getObject("id", UUID.class);
            assertThat(rows.getLong("holds")).as("hold count for %s", paymentId).isEqualTo(1);
            assertThat(rows.getLong("captures")).as("capture count for %s", paymentId).isEqualTo(1);
            assertThat(rows.getLong("settlements"))
                .as("settlement count for %s", paymentId)
                .isEqualTo(1);
            checked++;
          }
        }
        assertThat(checked).isEqualTo(paymentIds.size());
      } finally {
        ids.free();
      }
    }
  }

  private static void assertBankConfirmations(FaultStack stack, List<UUID> paymentIds)
      throws SQLException {
    HttpClient http = HttpClient.newHttpClient();
    try (Connection connection = stack.jdbc();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT external_reference FROM ledger.external_payment WHERE id = ANY(?)")) {
      Array ids = connection.createArrayOf("uuid", paymentIds.toArray(UUID[]::new));
      try {
        query.setArray(1, ids);
        try (ResultSet rows = query.executeQuery()) {
          int checked = 0;
          while (rows.next()) {
            String reference = rows.getString(1);
            HttpRequest request =
                HttpRequest.newBuilder(URI.create(stack.extbankUrl() + "/payments/" + reference))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Api-Key", stack.extbankApiKey())
                    .GET()
                    .build();
            HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("bank payment lookup").isEqualTo(200);
            assertThat(JSON.readTree(response.body()).get("confirmations").asInt())
                .as("bank confirmations for %s", reference)
                .isEqualTo(1);
            checked++;
          }
          assertThat(checked).isEqualTo(paymentIds.size());
        }
      } finally {
        ids.free();
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("failed to read external bank payment", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("external bank payment lookup interrupted", e);
    }
  }

  private static FaultResult withPaymentMeasurements(
      FaultResult result, Map<String, Integer> paymentStates, Long createdOrLost) {
    return new FaultResult(
        result.scenario(),
        result.strategy(),
        result.operations(),
        result.posted(),
        result.rejected(),
        result.unresolved(),
        result.duplicatePostings(),
        result.missingPostings(),
        result.violations(),
        result.balanceSum(),
        null,
        null,
        null,
        paymentStates,
        createdOrLost,
        null);
  }

  private static FaultResult withMeasurements(
      FaultResult result,
      Integer duplicateScheduled,
      Integer missingEvents,
      Integer duplicateEvents) {
    return new FaultResult(
        result.scenario(),
        result.strategy(),
        result.operations(),
        result.posted(),
        result.rejected(),
        result.unresolved(),
        result.duplicatePostings(),
        result.missingPostings(),
        result.violations(),
        result.balanceSum(),
        duplicateScheduled,
        missingEvents,
        duplicateEvents,
        result.paymentStates(),
        result.createdOrLost(),
        null);
  }

  private static void stopWorkers(FaultStack stack) {
    for (String alias : List.of("worker", "worker2")) {
      try {
        if (stack.running(alias)) {
          stack.kill(alias);
        }
      } catch (IllegalStateException ignored) {
        return;
      }
    }
  }

  private static RuntimeException failure(String message, Exception cause) {
    if (cause instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
    return new IllegalStateException(message, cause);
  }
}
