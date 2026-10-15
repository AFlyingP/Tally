package tally.ledger.recon;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tally.extbank.ExtbankApplication;
import tally.ledger.LedgerApplication;
import tally.ledger.config.LedgerProperties;
import tally.ledger.payments.PaymentOrchestrator;
import tally.ledger.posting.StrategyRunner;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tally.platform.testing.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String API_KEY = "k".repeat(48);
  private static final String RUNS = "/v1/reconciliation/runs";
  private static final String HEADER = "reference,creditor_account,amount,currency,value_date\n";
  private final MutableClock clock = new MutableClock(Instant.parse("2026-03-01T12:00:00Z"));
  private ConfigurableApplicationContext bankContext;
  private ConfigurableApplicationContext context;
  private String bankUrl;
  private LocalDate date;

  @BeforeAll
  void start() {
    bankContext =
        ExtbankApplication.builder()
            .initializers(
                initializer ->
                    ((GenericApplicationContext) initializer)
                        .registerBean(Clock.class, () -> clock))
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=extbank_app",
                "--spring.datasource.password=" + PASSWORD,
                "--spring.datasource.hikari.maximum-pool-size=4",
                "--tally.extbank.api-key=" + API_KEY);
    bankUrl = "http://localhost:" + bankContext.getEnvironment().getProperty("local.server.port");
    context = ledger(bankUrl);
  }

  @BeforeEach
  void setDate(TestInfo test) {
    int day =
        switch (test.getTestMethod().orElseThrow().getName()) {
          case "cleanStatementHasNoBreaks" -> 1;
          case "classifiesEachBreakType" -> 2;
          case "rerunWithSameFileReturnsExistingRun" -> 3;
          case "correctedFileCreatesLinkedRun" -> 4;
          case "malformedFileFailsRunWithLineAndReason" -> 5;
          case "statementUnavailableStoresNothing" -> 6;
          case "futureBusinessDateIsRejected" -> 7;
          case "runWritesOneAuditRow" -> 8;
          case "scopes" -> 9;
          case "jobRunsOncePerDateAfterConfiguredTime" -> 10;
          default -> throw new IllegalArgumentException("unknown test");
        };
    date = LocalDate.of(2026, 3, day);
    clock.set(Instant.parse(date + "T12:00:00Z"));
  }

  @AfterAll
  void stop() {
    if (context != null) {
      context.close();
    }
    if (bankContext != null) {
      bankContext.close();
    }
  }

  @Test
  void cleanStatementHasNoBreaks() {
    settle(3);
    JsonNode run = run(null, 201);
    assertThat(run.get("business_date").asString()).isEqualTo(date.toString());
    assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(run.get("statement_lines").asInt()).isEqualTo(3);
    assertThat(run.get("ledger_payments").asInt()).isEqualTo(3);
    assertThat(run.get("matched").asInt()).isEqualTo(3);
    assertThat(run.get("break_count").asInt()).isZero();
    assertThat(run.get("breaks").isEmpty()).isTrue();
  }

  @Test
  void classifiesEachBreakType() {
    List<JsonNode> payments = settle(5);
    String missing = reference(payments.get(0));
    String amount = reference(payments.get(1));
    String currency = reference(payments.get(2));
    String wrongDate = reference(payments.get(3));
    String duplicate = reference(payments.get(4));
    String added = "SEED-00000001";
    String csv =
        HEADER
            + line(amount, 102, "EUR", date)
            + line(currency, 102, "USD", date)
            + line(wrongDate, 103, "EUR", date.minusDays(1))
            + line(duplicate, 104, "EUR", date)
            + line(duplicate, 104, "EUR", date)
            + line(added, 100, "EUR", date);
    JsonNode run = run(csv, 201);
    assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(run.get("break_count").asInt()).isEqualTo(6);
    assertThat(run.get("statement_lines").asInt()).isEqualTo(6);
    assertThat(run.get("ledger_payments").asInt()).isEqualTo(5);
    assertThat(run.get("matched").asInt()).isZero();
    Map<String, JsonNode> breaks = new HashMap<>();
    List<String> references = new ArrayList<>();
    for (JsonNode item : run.get("breaks")) {
      references.add(item.get("reference").asString());
      breaks.put(item.get("reference").asString(), item);
    }
    assertThat(references).hasSize(6).isSorted();
    assertThat(breaks.keySet())
        .containsExactlyInAnyOrder(missing, amount, currency, wrongDate, duplicate, added);
    assertBreak(breaks.get(missing), "MISSING_IN_STATEMENT", 100L, null);
    assertBreak(breaks.get(amount), "AMOUNT_MISMATCH", 101L, 102L);
    assertBreak(breaks.get(currency), "CURRENCY_MISMATCH", 102L, 102L);
    assertBreak(breaks.get(wrongDate), "DATE_MISMATCH", 103L, 103L);
    assertBreak(breaks.get(duplicate), "DUPLICATE_IN_STATEMENT", 104L, 104L);
    assertBreak(breaks.get(added), "MISSING_IN_LEDGER", null, 100L);
    assertThat(breaks.get(missing).get("line_number").isNull()).isTrue();
    assertThat(breaks.get(currency).get("ledger_currency").asString()).isEqualTo("EUR");
    assertThat(breaks.get(currency).get("statement_currency").asString()).isEqualTo("USD");
    assertThat(breaks.get(wrongDate).get("ledger_date").asString()).isEqualTo(date.toString());
    assertThat(breaks.get(wrongDate).get("statement_date").asString())
        .isEqualTo(date.minusDays(1).toString());
    assertThat(breaks.get(duplicate).get("line_number").asInt()).isEqualTo(5);
    HttpResponse<String> fetched = api("GET", RUNS + "/" + id(run), Role.ADMIN, null);
    assertThat(fetched.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(fetched.body())).isEqualTo(run);
  }

  @Test
  void rerunWithSameFileReturnsExistingRun() throws Exception {
    settle(1);
    String csv = statement();
    JsonNode first = run(csv, 201);
    JsonNode second = run(csv, 200);
    assertThat(second).isEqualTo(first);
    assertThat(
            count(
                "SELECT count(*) FROM ledger.recon_run WHERE business_date = ?"
                    + " AND file_sha256 = (SELECT file_sha256 FROM ledger.recon_run WHERE id = ?)",
                date,
                id(first)))
        .isEqualTo(1);
  }

  @Test
  void correctedFileCreatesLinkedRun() {
    List<JsonNode> payments = settle(1);
    JsonNode first = run(HEADER, 201);
    clock.advance(Duration.ofSeconds(1));
    JsonNode second = run(HEADER + line(reference(payments.getFirst()), 100, "EUR", date), 201);
    assertThat(second.get("previous_run_id").asString()).isEqualTo(id(first).toString());
    assertThat(second.get("break_count").asInt()).isZero();
    HttpResponse<String> listed = api("GET", RUNS + "?business_date=" + date, Role.ADMIN, null);
    assertThat(listed.statusCode()).isEqualTo(200);
    JsonNode items = JSON.readTree(listed.body()).get("items");
    assertThat(items.size()).isEqualTo(2);
    assertThat(id(items.get(0))).isEqualTo(id(first));
    assertThat(id(items.get(1))).isEqualTo(id(second));
    assertThat(items.get(0).has("breaks")).isFalse();
    assertThat(items.get(1).has("breaks")).isFalse();
  }

  @Test
  void malformedFileFailsRunWithLineAndReason() throws Exception {
    List<JsonNode> payments = settle(2);
    String csv =
        HEADER
            + line(reference(payments.getFirst()), 100, "EUR", date)
            + reference(payments.get(1))
            + ",DEMO-OK,abc,EUR,"
            + date
            + "\n";
    JsonNode first = run(csv, 201);
    assertThat(first.get("status").asString()).isEqualTo("FAILED");
    assertThat(first.get("failure_line").asInt()).isEqualTo(3);
    assertThat(first.get("failure_reason").asString()).isEqualTo("invalid amount");
    assertThat(first.get("break_count").asInt()).isZero();
    assertThat(first.get("statement_lines").asInt()).isZero();
    assertThat(first.get("ledger_payments").asInt()).isZero();
    assertThat(first.get("matched").asInt()).isZero();
    assertThat(first.get("breaks").isEmpty()).isTrue();
    assertThat(count("SELECT count(*) FROM ledger.recon_break WHERE run_id = ?", id(first)))
        .isZero();
    assertThat(run(csv, 200)).isEqualTo(first);
  }

  @Test
  void statementUnavailableStoresNothing() throws Exception {
    try (ConfigurableApplicationContext unavailable = ledger("http://localhost:1")) {
      HttpResponse<String> response = api(unavailable, "POST", RUNS, Role.ADMIN, body(null));
      assertThat(response.statusCode()).isEqualTo(502);
      assertThat(JSON.readTree(response.body()).get("error").get("code").asString())
          .isEqualTo("STATEMENT_UNAVAILABLE");
    }
    assertThat(count("SELECT count(*) FROM ledger.recon_run WHERE business_date = ?", date))
        .isZero();
  }

  @Test
  void futureBusinessDateIsRejected() {
    HttpResponse<String> response =
        api(
            "POST",
            RUNS,
            Role.ADMIN,
            JSON.writeValueAsString(
                Map.of("business_date", date.plusDays(1).toString(), "statement_csv", HEADER)));
    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode error = JSON.readTree(response.body()).get("error");
    assertThat(error.get("code").asString()).isEqualTo("VALIDATION_FAILED");
    assertThat(error.get("details").get(0).get("field").asString()).isEqualTo("business_date");
  }

  @Test
  void runWritesOneAuditRow() throws Exception {
    JsonNode run = run(HEADER, 201);
    run(HEADER, 200);
    assertThat(
            count(
                "SELECT count(*) FROM ledger.audit_log WHERE resource_type = 'recon_run'"
                    + " AND resource_id = ? AND action = 'recon.run'",
                id(run).toString()))
        .isEqualTo(1);
  }

  @Test
  void jobRunsOncePerDateAfterConfiguredTime() throws Exception {
    settle(1);
    ReconStore store = context.getBean(ReconStore.class);
    ReconJob job =
        new ReconJob(
            store,
            context.getBean(ReconService.class),
            context.getBean(StrategyRunner.class),
            context.getBean(LedgerProperties.class),
            clock);
    assertThat(job.name()).isEqualTo("recon");
    assertThat(job.role()).isEqualTo("recon");
    assertThat(job.pollMs()).isEqualTo(60000);
    clock.set(Instant.parse("2026-03-11T00:29:00Z"));
    assertThat(job.runOnce()).isZero();
    try (Connection db = appJdbc()) {
      assertThat(store.latest(db, date)).isEmpty();
    }
    clock.set(Instant.parse("2026-03-11T00:31:00Z"));
    assertThat(job.runOnce()).isEqualTo(1);
    UUID runId;
    try (Connection db = appJdbc()) {
      ReconStore.RunRow row = store.latest(db, date).orElseThrow();
      assertThat(row.businessDate()).isEqualTo(LocalDate.of(2026, 3, 10));
      assertThat(row.status()).isEqualTo("COMPLETED");
      runId = row.id();
    }
    assertThat(
            count(
                "SELECT count(*) FROM ledger.audit_log WHERE resource_type = 'recon_run'"
                    + " AND resource_id = ? AND action = 'recon.run' AND actor = 'job:recon'",
                runId.toString()))
        .isEqualTo(1);
    assertThat(job.runOnce()).isZero();
  }

  @Test
  void scopes() {
    JsonNode run = run(HEADER, 201);
    assertThat(api("POST", RUNS, Role.OPERATOR, body(HEADER)).statusCode()).isEqualTo(403);
    assertThat(api("GET", RUNS + "?business_date=" + date, Role.AUDITOR, null).statusCode())
        .isEqualTo(200);
    assertThat(api("GET", RUNS + "/" + id(run), Role.AUDITOR, null).statusCode()).isEqualTo(200);
    assertThat(api("POST", RUNS, Role.AUDITOR, body(HEADER)).statusCode()).isEqualTo(403);
  }

  private ConfigurableApplicationContext ledger(String extbankUrl) {
    return LedgerApplication.builder()
        .initializers(
            initializer ->
                ((GenericApplicationContext) initializer).registerBean(Clock.class, () -> clock))
        .run(
            args(
                Map.of(
                    "tally.ledger.extbank.url",
                    extbankUrl,
                    "tally.ledger.extbank.api-key",
                    API_KEY,
                    "spring.datasource.hikari.maximum-pool-size",
                    "8",
                    "tally.ledger.recon.time-utc",
                    "00:30")));
  }

  private List<JsonNode> settle(int size) {
    JsonNode account = postJson("/v1/accounts", "{\"currency\":\"EUR\"}", 201);
    String source = account.get("id").asString();
    postJson(
        "/v1/deposits",
        "{\"account_id\":\"%s\",\"amount\":10000,\"currency\":\"EUR\"}".formatted(source),
        201);
    List<JsonNode> payments = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      payments.add(
          postJson(
              "/v1/external-payments",
              "{\"source_account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\",\"creditor_account\":\"DEMO-OK\"}"
                  .formatted(source, 100 + i),
              202));
    }
    PaymentOrchestrator orchestrator = context.getBean(PaymentOrchestrator.class);
    for (int attempt = 0; attempt < 20; attempt++) {
      orchestrator.runOnce();
      boolean settled = true;
      for (JsonNode payment : payments) {
        HttpResponse<String> response =
            api("GET", "/v1/external-payments/" + id(payment), Role.ADMIN, null);
        assertThat(response.statusCode()).isEqualTo(200);
        settled &= JSON.readTree(response.body()).get("state").asString().equals("SETTLED");
      }
      if (settled) {
        return payments;
      }
    }
    throw new AssertionError("payments did not settle");
  }

  private JsonNode postJson(String path, String body, int status) {
    HttpResponse<String> response = api("POST", path, Role.ADMIN, body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    return JSON.readTree(response.body());
  }

  private JsonNode run(String csv, int status) {
    return postJson(RUNS, body(csv), status);
  }

  private String body(String csv) {
    Map<String, String> body = new HashMap<>();
    body.put("business_date", date.toString());
    if (csv != null) {
      body.put("statement_csv", csv);
    }
    return JSON.writeValueAsString(body);
  }

  private HttpResponse<String> api(String method, String path, Role role, String body) {
    return api(context, method, path, role, body);
  }

  private HttpResponse<String> api(
      ConfigurableApplicationContext application,
      String method,
      String path,
      Role role,
      String body) {
    return send(
        request(url(application, path), token(role))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)));
  }

  private String statement() {
    HttpResponse<String> response =
        send(
            HttpRequest.newBuilder(URI.create(bankUrl + "/statements/" + date))
                .header("X-Api-Key", API_KEY)
                .GET());
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }

  private long count(String sql, Object... values) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement query = db.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) {
        query.setObject(i + 1, values[i]);
      }
      try (ResultSet row = query.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private static UUID id(JsonNode node) {
    return UUID.fromString(node.get("id").asString());
  }

  private static String reference(JsonNode payment) {
    return payment.get("external_reference").asString();
  }

  private static String line(String reference, long amount, String currency, LocalDate date) {
    return "%s,DEMO-OK,%d,%s,%s\n".formatted(reference, amount, currency, date);
  }

  private static void assertBreak(JsonNode item, String type, Long ledger, Long statement) {
    assertThat(item.get("type").asString()).isEqualTo(type);
    if (ledger == null) {
      assertThat(item.get("ledger_amount").isNull()).isTrue();
      assertThat(item.get("ledger_currency").isNull()).isTrue();
      assertThat(item.get("ledger_date").isNull()).isTrue();
    } else {
      assertThat(item.get("ledger_amount").asLong()).isEqualTo(ledger);
    }
    if (statement == null) {
      assertThat(item.get("statement_amount").isNull()).isTrue();
      assertThat(item.get("statement_currency").isNull()).isTrue();
      assertThat(item.get("statement_date").isNull()).isTrue();
    } else {
      assertThat(item.get("statement_amount").asLong()).isEqualTo(statement);
    }
  }
}
