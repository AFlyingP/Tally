package tally.extbank;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
class ExtbankStartIT {

  private static final String PASSWORD = "test-password";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void readyReportsDb() throws Exception {
    try (PostgreSQLContainer postgres = postgres()) {
      postgres.start();
      try (ConfigurableApplicationContext app =
          ExtbankApplication.builder()
              .run(
                  "--server.port=0",
                  "--spring.datasource.url=" + postgres.getJdbcUrl(),
                  "--spring.datasource.password=" + PASSWORD)) {
        String port = app.getEnvironment().getProperty("local.server.port");
        HttpResponse<String> ready =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ready"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());

        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(ready.body()))
            .isEqualTo(JSON.readTree("{\"status\":\"UP\",\"checks\":{\"db\":\"UP\"}}"));
      }
    }
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
