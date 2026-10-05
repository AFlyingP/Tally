package tally.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import tally.monitor.testing.MonitorIT;
import tools.jackson.databind.json.JsonMapper;

class MonitorStartIT extends MonitorIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void readyReportsDbAndKafka() {
    HttpResponse<String> ready = get("/ready", null);

    assertThat(ready.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(ready.body()))
        .isEqualTo(
            JSON.readTree("{\"status\":\"UP\",\"checks\":{\"db\":\"UP\",\"kafka\":\"UP\"}}"));
  }

  @Test
  void unknownPathNeedsAToken() {
    assertThat(get("/v1/cases", null).statusCode()).isEqualTo(401);
  }

  @Test
  void mainStartsTheService() throws Exception {
    AtomicReference<ConfigurableApplicationContext> started = new AtomicReference<>();
    SpringApplication.withHook(
        application ->
            new SpringApplicationRunListener() {
              @Override
              public void started(ConfigurableApplicationContext context, Duration timeTaken) {
                started.set(context);
              }
            },
        () -> MonitorApplication.main(args(Map.of())));

    try (ConfigurableApplicationContext service = started.get()) {
      String port = service.getEnvironment().getProperty("local.server.port");
      HttpResponse<String> health =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(health.statusCode()).isEqualTo(200);
    }
  }
}
