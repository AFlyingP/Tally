package tally.extbank;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tally.platform.testing.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtbankIT {

  private static final String PASSWORD = "test-password";
  private static final String API_KEY = "e".repeat(48);
  private static final Instant START = Instant.parse("2026-03-10T12:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private PostgreSQLContainer postgres;
  private ConfigurableApplicationContext app;
  private MutableClock clock;
  private String base;

  @BeforeAll
  void start() {
    postgres = postgres();
    postgres.start();
    clock = new MutableClock(START);
    app =
        ExtbankApplication.builder()
            .initializers(
                initializer ->
                    ((GenericApplicationContext) initializer)
                        .registerBean(Clock.class, () -> clock))
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=extbank_app",
                "--spring.datasource.password=" + PASSWORD,
                "--tally.extbank.api-key=" + API_KEY);
    base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  void stop() {
    if (app != null) {
      app.close();
    }
    if (postgres != null) {
      postgres.stop();
    }
  }

  @BeforeEach
  void clearRules() throws Exception {
    try (Connection connection =
            DriverManager.getConnection(postgres.getJdbcUrl(), "extbank_app", PASSWORD);
        PreparedStatement delete = connection.prepareStatement("DELETE FROM extbank.rule")) {
      delete.executeUpdate();
    }
  }

  @Test
  void acceptConfirmsWithValueDate() throws Exception {
    HttpResponse<String> first = postPayment("TEST-ACCEPT-01", "ACC-OK", "1200", "EUR");
    assertThat(first.statusCode()).isEqualTo(200);
    JsonNode body = json(first);
    assertThat(body.path("status").asString()).isEqualTo("CONFIRMED");
    assertThat(body.path("value_date").asString()).isEqualTo("2026-03-10");
    assertThat(body.path("confirmations").asInt()).isEqualTo(1);
    HttpResponse<String> second = get("/payments/TEST-ACCEPT-01");
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(json(second)).isEqualTo(body);
  }

  @Test
  void rejectRule() throws Exception {
    createRule("ACC-R", "REJECT", null, null);
    JsonNode rejected = json(postPayment("TEST-REJECT-01", "ACC-R", "10", "EUR"));
    assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
    assertThat(rejected.path("reject_reason").asString()).isEqualTo("rejected by rule");
    JsonNode accepted = json(postPayment("TEST-REJECT-02", "ACC-OTHER", "10", "EUR"));
    assertThat(accepted.path("status").asString()).isEqualTo("CONFIRMED");
  }

  @Test
  void delayRuleConfirmsAfterDelay() throws Exception {
    createRule("ACC-D", "DELAY", "400", null);
    long started = System.nanoTime();
    CompletableFuture<HttpResponse<String>> first =
        CLIENT.sendAsync(
            request(
                "POST", "/payments", paymentBody("TEST-DELAY-01", "ACC-D", "20", "EUR"), API_KEY),
            HttpResponse.BodyHandlers.ofString());
    JsonNode pending = null;
    long deadline = System.nanoTime() + Duration.ofMillis(350).toNanos();
    while (System.nanoTime() < deadline) {
      HttpResponse<String> status = get("/payments/TEST-DELAY-01");
      if (status.statusCode() == 200) {
        pending = json(status);
        break;
      }
      Thread.sleep(10);
    }
    assertThat(pending).isNotNull();
    assertThat(pending.path("status").asString()).isEqualTo("PENDING");
    HttpResponse<String> completed = first.get(5, TimeUnit.SECONDS);
    assertThat(completed.statusCode()).isEqualTo(200);
    assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis())
        .isGreaterThanOrEqualTo(400);
    assertThat(json(completed).path("status").asString()).isEqualTo("CONFIRMED");
  }

  @Test
  void dropResponseStoresThenAnswers504() throws Exception {
    createRule("ACC-DROP", "DROP_RESPONSE", "300", null);
    long started = System.nanoTime();
    HttpResponse<String> dropped = postPayment("TEST-DROP-01", "ACC-DROP", "30", "EUR");
    assertThat(dropped.statusCode()).isEqualTo(504);
    assertThat(dropped.body()).isEmpty();
    assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis())
        .isGreaterThanOrEqualTo(300);
    JsonNode stored = json(get("/payments/TEST-DROP-01"));
    assertThat(stored.path("status").asString()).isEqualTo("CONFIRMED");
  }

  @Test
  void confirmTwiceSetsTwoConfirmations() throws Exception {
    createRule("ACC-TWICE", "CONFIRM_TWICE", null, null);
    JsonNode body = json(postPayment("TEST-TWICE-01", "ACC-TWICE", "40", "EUR"));
    assertThat(body.path("confirmations").asInt()).isEqualTo(2);
  }

  @Test
  void serverErrorStoresNothing() throws Exception {
    createRule("ACC-ERR", "SERVER_ERROR", null, null);
    HttpResponse<String> first = postPayment("TEST-ERROR-01", "ACC-ERR", "50", "EUR");
    assertThat(first.statusCode()).isEqualTo(500);
    assertThat(json(first).path("error").path("code").asString()).isEqualTo("INTERNAL_ERROR");
    HttpResponse<String> second = get("/payments/TEST-ERROR-01");
    assertThat(second.statusCode()).isEqualTo(404);
  }

  @Test
  void remainingLimitsRuleUse() throws Exception {
    createRule("ACC-LIMIT", "SERVER_ERROR", null, "2");
    assertThat(postPayment("TEST-LIMIT-01", "ACC-LIMIT", "60", "EUR").statusCode()).isEqualTo(500);
    assertThat(postPayment("TEST-LIMIT-02", "ACC-LIMIT", "60", "EUR").statusCode()).isEqualTo(500);
    assertThat(postPayment("TEST-LIMIT-03", "ACC-LIMIT", "60", "EUR").statusCode()).isEqualTo(200);
    JsonNode listed = json(get("/control/rules"));
    assertThat(listed.path("items").get(0).path("remaining").asInt()).isZero();
  }

  @Test
  void firstMatchingRuleWins() throws Exception {
    createRule("ACC-1", "REJECT", null, null);
    createRule(null, "ACCEPT", null, null);
    assertThat(json(postPayment("TEST-FIRST-01", "ACC-1", "70", "EUR")).path("status").asString())
        .isEqualTo("REJECTED");
    assertThat(json(postPayment("TEST-FIRST-02", "ACC-2", "70", "EUR")).path("status").asString())
        .isEqualTo("CONFIRMED");
  }

  @Test
  void resubmitSameReferenceReturnsStoredPayment() throws Exception {
    createRule("ACC-SAME", "ACCEPT", null, "2");
    HttpResponse<String> first = postPayment("TEST-SAME-01", "ACC-SAME", "80", "EUR");
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(json(get("/control/rules")).path("items").get(0).path("remaining").asInt())
        .isEqualTo(1);
    HttpResponse<String> second = postPayment("TEST-SAME-01", "ACC-SAME", "80", "EUR");
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(json(second)).isEqualTo(json(first));
    assertThat(json(get("/control/rules")).path("items").get(0).path("remaining").asInt())
        .isEqualTo(1);
  }

  @Test
  void resubmitWithDifferentAmountConflicts() throws Exception {
    postPayment("TEST-CONFLICT-01", "ACC-C", "90", "EUR");
    HttpResponse<String> conflict = postPayment("TEST-CONFLICT-01", "ACC-C", "91", "EUR");
    assertThat(conflict.statusCode()).isEqualTo(409);
    assertThat(json(conflict).path("error").path("code").asString())
        .isEqualTo("REFERENCE_CONFLICT");
  }

  @Test
  void validationErrors() throws Exception {
    assertValidation(paymentBody("abc", "ACC-VALID", "1", "EUR"));
    assertValidation(paymentBody("TEST-VALID-01", "ACC-VALID", "0", "EUR"));
    assertValidation(paymentBody("TEST-VALID-02", "ACC-VALID", "1", "eur"));
    HttpResponse<String> unknown =
        postJson("/control/rules", "{\"creditor_account\":null,\"behaviour\":\"UNKNOWN\"}");
    assertThat(unknown.statusCode()).isEqualTo(400);
    assertThat(json(unknown).path("error").path("code").asString()).isEqualTo("MALFORMED_REQUEST");
  }

  @Test
  void apiKeyIsRequired() throws Exception {
    assertUnauthorized("POST", "/payments", paymentBody("TEST-KEY-01", "ACC-KEY", "1", "EUR"));
    assertUnauthorized("GET", "/payments/TEST-KEY-01", null);
    assertUnauthorized("GET", "/statements/2026-03-10", null);
    assertUnauthorized(
        "POST", "/control/rules", "{\"creditor_account\":null,\"behaviour\":\"ACCEPT\"}");
    assertThat(send("GET", "/health", null, null).statusCode()).isEqualTo(200);
  }

  @Test
  void deleteRulesClearsAll() throws Exception {
    createRule(null, "ACCEPT", null, null);
    HttpResponse<String> deleted = send("DELETE", "/control/rules", null, API_KEY);
    assertThat(deleted.statusCode()).isEqualTo(204);
    assertThat(json(get("/control/rules")).path("items").isEmpty()).isTrue();
  }

  private void createRule(String creditor, String behaviour, String delay, String remaining)
      throws Exception {
    String body =
        "{\"creditor_account\":%s,\"behaviour\":\"%s\",\"delay_ms\":%s,\"remaining\":%s}"
            .formatted(
                creditor == null ? "null" : "\"" + creditor + "\"",
                behaviour,
                delay == null ? "0" : delay,
                remaining == null ? "null" : remaining);
    assertThat(postJson("/control/rules", body).statusCode()).isEqualTo(201);
  }

  private void assertValidation(String body) throws Exception {
    HttpResponse<String> response = postJson("/payments", body);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json(response).path("error").path("code").asString()).isEqualTo("VALIDATION_FAILED");
  }

  private void assertUnauthorized(String method, String path, String body) throws Exception {
    HttpResponse<String> missing = send(method, path, body, null);
    assertThat(missing.statusCode()).isEqualTo(401);
    assertThat(json(missing).path("error").path("code").asString()).isEqualTo("UNAUTHENTICATED");
    HttpResponse<String> wrong = send(method, path, body, "wrong-test-key");
    assertThat(wrong.statusCode()).isEqualTo(401);
    assertThat(json(wrong).path("error").path("code").asString()).isEqualTo("UNAUTHENTICATED");
  }

  private HttpResponse<String> postPayment(
      String reference, String creditor, String amount, String currency) throws Exception {
    return postJson("/payments", paymentBody(reference, creditor, amount, currency));
  }

  private static String paymentBody(
      String reference, String creditor, String amount, String currency) {
    return "{\"reference\":\"%s\",\"creditor_account\":\"%s\",\"amount\":%s,\"currency\":\"%s\"}"
        .formatted(reference, creditor, amount, currency);
  }

  private HttpResponse<String> postJson(String path, String body) throws Exception {
    return send("POST", path, body, API_KEY);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send("GET", path, null, API_KEY);
  }

  private HttpResponse<String> send(String method, String path, String body, String key)
      throws Exception {
    return CLIENT.send(request(method, path, body, key), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest request(String method, String path, String body, String key) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
    if (key != null) {
      request.header("X-Api-Key", key);
    }
    if (body != null) {
      request.header("Content-Type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(body));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return request.build();
  }

  private static JsonNode json(HttpResponse<String> response) throws Exception {
    return JSON.readTree(response.body());
  }

  private static PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:16.15-alpine")
        .withDatabaseName("tally")
        .withUsername("postgres")
        .withPassword(PASSWORD)
        .withEnv("LEDGER_OWNER_PASSWORD", PASSWORD)
        .withEnv("LEDGER_APP_PASSWORD", PASSWORD)
        .withEnv("MONITOR_DB_PASSWORD", PASSWORD)
        .withEnv("AUTH_DB_PASSWORD", PASSWORD)
        .withEnv("EXTBANK_DB_PASSWORD", PASSWORD)
        .withCopyFileToContainer(
            MountableFile.forHostPath("../deploy/postgres/init.sh", 0755),
            "/docker-entrypoint-initdb.d/init.sh");
  }
}
