package tally.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LedgerStartIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void healthAndReadyWithApiRole() {
    HttpResponse<String> health = get("/health", null);
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(health.body()).get("status").asString()).isEqualTo("UP");

    HttpResponse<String> ready = get("/ready", null);
    assertThat(ready.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(ready.body()))
        .isEqualTo(JSON.readTree("{\"status\":\"UP\",\"checks\":{\"db\":\"UP\"}}"));
  }

  @Test
  void unknownV1PathIsNotFoundWithToken() {
    HttpResponse<String> withToken = get("/v1/nothing", token(Role.OPERATOR));
    assertThat(withToken.statusCode()).isEqualTo(404);
    assertThat(errorCode(withToken)).isEqualTo("NOT_FOUND");

    HttpResponse<String> withoutToken = get("/v1/nothing", null);
    assertThat(withoutToken.statusCode()).isEqualTo(401);
    assertThat(errorCode(withoutToken)).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void workerRolesHideTheApi() {
    try (ConfigurableApplicationContext worker = startApp(Map.of("tally.ledger.roles", "expiry"))) {
      HttpResponse<String> api =
          send(request(url(worker, "/v1/accounts/x"), token(Role.OPERATOR)).GET());
      assertThat(api.statusCode()).isEqualTo(404);
      assertThat(errorCode(api)).isEqualTo("NOT_FOUND");

      HttpResponse<String> health = send(request(url(worker, "/health"), null).GET());
      assertThat(health.statusCode()).isEqualTo(200);
    }
  }

  @Test
  void rejectsUnknownRoleAtStartup() {
    assertThatThrownBy(() -> startApp(Map.of("tally.ledger.roles", "api,bogus")))
        .rootCause()
        .hasMessageContaining("invalid configuration: tally.ledger.roles");
  }

  @Test
  void mainStartsTheService() {
    AtomicReference<ConfigurableApplicationContext> started = new AtomicReference<>();
    SpringApplication.withHook(
        application ->
            new SpringApplicationRunListener() {
              @Override
              public void started(ConfigurableApplicationContext context, Duration timeTaken) {
                started.set(context);
              }
            },
        () -> LedgerApplication.main(args(Map.of())));

    try (ConfigurableApplicationContext service = started.get()) {
      HttpResponse<String> health = send(request(url(service, "/health"), null).GET());
      assertThat(health.statusCode()).isEqualTo(200);
    }
  }

  private static String errorCode(HttpResponse<String> response) {
    JsonNode error = JSON.readTree(response.body()).get("error");
    return error.get("code").asString();
  }
}
