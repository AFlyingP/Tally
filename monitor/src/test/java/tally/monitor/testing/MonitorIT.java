package tally.monitor.testing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tally.monitor.MonitorApplication;
import tally.platform.security.Role;
import tally.platform.testing.TestIssuer;

/** Base class of the monitor integration tests: database, broker, issuer, one default app. */
@Tag("integration")
public abstract class MonitorIT {

  protected static final String PASSWORD = "test-password";

  protected static final PostgreSQLContainer POSTGRES = postgres();
  protected static final KafkaContainer KAFKA = kafka();
  protected static final TestIssuer ISSUER = TestIssuer.start();

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static ConfigurableApplicationContext defaultApp;

  private static PostgreSQLContainer postgres() {
    PostgreSQLContainer container =
        new PostgreSQLContainer("postgres:16.15-alpine")
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
    container.start();
    return container;
  }

  private static KafkaContainer kafka() {
    KafkaContainer container = new KafkaContainer("apache/kafka:4.2.2");
    container.start();
    return container;
  }

  /** Starts a monitor process on a free port; the overrides win over the test defaults. */
  protected static ConfigurableApplicationContext startApp(Map<String, String> overrides) {
    return MonitorApplication.builder().run(args(overrides));
  }

  /** The command line of a test process. */
  protected static String[] args(Map<String, String> overrides) {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("server.port", "0");
    settings.put("spring.datasource.url", POSTGRES.getJdbcUrl());
    settings.put("spring.datasource.password", PASSWORD);
    settings.put("tally.monitor.kafka.bootstrap", KAFKA.getBootstrapServers());
    settings.put("tally.auth.jwks-uri", ISSUER.jwksUri());
    settings.put("tally.auth.issuer", ISSUER.issuer());
    settings.putAll(overrides);
    List<String> args = new ArrayList<>();
    settings.forEach((key, value) -> args.add("--" + key + "=" + value));
    return args.toArray(String[]::new);
  }

  protected static synchronized ConfigurableApplicationContext app() {
    if (defaultApp == null) {
      defaultApp = startApp(Map.of());
    }
    return defaultApp;
  }

  protected String url(String path) {
    return "http://localhost:" + app().getEnvironment().getProperty("local.server.port") + path;
  }

  protected HttpResponse<String> get(String path, String token) {
    return send(request(path, token).GET());
  }

  protected HttpResponse<String> post(String path, String token, String json, String... headers) {
    HttpRequest.Builder request =
        request(path, token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
    if (headers.length > 0) {
      request.headers(headers);
    }
    return send(request);
  }

  private HttpRequest.Builder request(String path, String token) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return request;
  }

  private static HttpResponse<String> send(HttpRequest.Builder request) {
    try {
      return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  protected Connection appJdbc() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "monitor_app", PASSWORD);
  }

  protected Connection superJdbc() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", PASSWORD);
  }

  protected String token(Role role) {
    return ISSUER.token(role);
  }
}
