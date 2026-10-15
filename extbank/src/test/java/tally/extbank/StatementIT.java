package tally.extbank;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tally.platform.testing.MutableClock;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatementIT {

  private static final String PASSWORD = "test-password";
  private static final String API_KEY = "s".repeat(48);
  private static final Instant START = Instant.parse("2026-03-10T12:00:00Z");
  private static final String HEADER = "reference,creditor_account,amount,currency,value_date";
  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PostgreSQLContainer postgres;
  private ConfigurableApplicationContext app;
  private String base;

  @BeforeAll
  void start() {
    postgres = postgres();
    postgres.start();
    MutableClock clock = new MutableClock(START);
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

  @Test
  void statementListsConfirmedPaymentsOfTheDate() throws Exception {
    payment("TEST-STAT-01", "ACC-STAT-1", "100", "EUR");
    payment("TEST-STAT-02", "ACC-STAT-2", "200", "USD");
    payment("TEST-STAT-03", "ACC-STAT-3", "300", "GBP");
    rule("ACC-STAT-R", "REJECT");
    payment("TEST-STAT-04", "ACC-STAT-R", "400", "EUR");
    rule("ACC-STAT-5", "CONFIRM_TWICE");
    payment("TEST-STAT-05", "ACC-STAT-5", "500", "EUR");

    HttpResponse<String> statement = get("/statements/2026-03-10");
    assertThat(statement.statusCode()).isEqualTo(200);
    assertThat(statement.headers().firstValue("Content-Type").orElseThrow())
        .isEqualTo("text/csv;charset=utf-8");
    List<String> lines = statement.body().lines().toList();
    assertThat(lines).hasSize(6);
    assertThat(lines.get(0)).isEqualTo(HEADER);
    assertThat(lines.subList(1, lines.size()))
        .containsExactly(
            "TEST-STAT-01,ACC-STAT-1,100,EUR,2026-03-10",
            "TEST-STAT-02,ACC-STAT-2,200,USD,2026-03-10",
            "TEST-STAT-03,ACC-STAT-3,300,GBP,2026-03-10",
            "TEST-STAT-05,ACC-STAT-5,500,EUR,2026-03-10",
            "TEST-STAT-05,ACC-STAT-5,500,EUR,2026-03-10");
    Pattern reference = Pattern.compile("^[A-Z0-9-]{8,40}$");
    Pattern creditor = Pattern.compile("^[A-Z0-9-]{4,34}$");
    Pattern amount = Pattern.compile("^[1-9][0-9]{0,17}$");
    Pattern currency = Pattern.compile("^[A-Z]{3}$");
    Pattern date = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    for (String line : lines.subList(1, lines.size())) {
      String[] fields = line.split(",", -1);
      assertThat(fields).hasSize(5);
      assertThat(reference.matcher(fields[0]).matches()).isTrue();
      assertThat(creditor.matcher(fields[1]).matches()).isTrue();
      assertThat(amount.matcher(fields[2]).matches()).isTrue();
      assertThat(currency.matcher(fields[3]).matches()).isTrue();
      assertThat(date.matcher(fields[4]).matches()).isTrue();
      assertThat(java.time.LocalDate.parse(fields[4]))
          .isEqualTo(java.time.LocalDate.of(2026, 3, 10));
    }
  }

  @Test
  void statementOfDateWithoutPaymentsIsHeaderOnly() throws Exception {
    HttpResponse<String> statement = get("/statements/2026-03-11");
    assertThat(statement.statusCode()).isEqualTo(200);
    assertThat(statement.body()).isEqualTo(HEADER + "\n");
  }

  @Test
  void invalidDateIsRejected() throws Exception {
    HttpResponse<String> response = get("/statements/2026-13-40");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(response.body()).path("error").path("code").asString())
        .isEqualTo("VALIDATION_FAILED");
  }

  private void payment(String reference, String creditor, String amount, String currency)
      throws Exception {
    String body =
        "{\"reference\":\"%s\",\"creditor_account\":\"%s\",\"amount\":%s,\"currency\":\"%s\"}"
            .formatted(reference, creditor, amount, currency);
    assertThat(send("POST", "/payments", body).statusCode()).isEqualTo(200);
  }

  private void rule(String creditor, String behaviour) throws Exception {
    String body =
        "{\"creditor_account\":\"%s\",\"behaviour\":\"%s\"}".formatted(creditor, behaviour);
    assertThat(send("POST", "/control/rules", body).statusCode()).isEqualTo(201);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send("GET", path, null);
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path)).header("X-Api-Key", API_KEY);
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
