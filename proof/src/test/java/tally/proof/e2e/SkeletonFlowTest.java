package tally.proof.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tally.platform.security.Role;
import tally.proof.harness.Http;
import tally.proof.harness.Stack;
import tools.jackson.databind.JsonNode;

@Tag("e2e")
class SkeletonFlowTest {

  @Test
  void requestFlowsThroughTheStack() {
    String token = Stack.token(Role.OPERATOR);
    assertThat(token).isNotBlank();

    Http.Response created =
        Http.post(Stack.url(8080, "/v1/accounts"), token, "{\"currency\":\"EUR\"}");
    assertThat(created.status()).isEqualTo(201);
    assertThat(created.json().get("kind").asString()).isEqualTo("CUSTOMER");
    assertThat(created.header("X-Request-Id")).isNotBlank();

    for (int port : new int[] {8080, 8081, 8082, 8083, 8084}) {
      Http.Response ready = Http.get(Stack.url(port, "/ready"), null);
      assertThat(ready.status()).as("ready on port %d", port).isEqualTo(200);
      JsonNode body = ready.json();
      assertThat(body.get("status").asString()).isEqualTo("UP");
      for (JsonNode check : body.get("checks")) {
        assertThat(check.asString()).as("checks on port %d", port).isEqualTo("UP");
      }
    }
  }
}
