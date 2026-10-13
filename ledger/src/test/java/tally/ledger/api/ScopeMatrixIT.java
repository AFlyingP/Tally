package tally.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.json.JsonMapper;

class ScopeMatrixIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void everyEndpointEnforcesItsScopeForEveryRole() {
    UUID account = create();
    UUID frozen = create();
    UUID closed = create();
    UUID destination = create();
    HttpResponse<String> deposit =
        post(
            "/v1/deposits",
            token(Role.OPERATOR),
            amount(account, 100),
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(deposit.statusCode()).isEqualTo(201);
    UUID transaction = UUID.fromString(JSON.readTree(deposit.body()).get("id").asString());
    Instant expiresAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
    HttpResponse<String> placed =
        post(
            "/v1/holds",
            token(Role.OPERATOR),
            hold(account, 10, expiresAt),
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(placed.statusCode()).isEqualTo(201);
    UUID holdId = UUID.fromString(JSON.readTree(placed.body()).get("id").asString());
    String accountPath = "/v1/accounts/" + account;
    String transactionPath = "/v1/transactions/" + transaction;
    List<Endpoint> endpoints =
        new ArrayList<>(
            List.of(
                new Endpoint(
                    "POST", "/v1/accounts", "{\"currency\":\"EUR\"}", "accounts:write", false),
                new Endpoint("GET", accountPath, "", "accounts:read", false),
                new Endpoint("GET", accountPath + "/balance", "", "accounts:read", false),
                new Endpoint("GET", accountPath + "/entries", "", "accounts:read", false),
                new Endpoint(
                    "POST", "/v1/accounts/" + frozen + "/freeze", "", "accounts:admin", false),
                new Endpoint(
                    "POST", "/v1/accounts/" + closed + "/close", "", "accounts:admin", false),
                new Endpoint("POST", "/v1/deposits", amount(account, 1), "money:write", true),
                new Endpoint("POST", "/v1/withdrawals", amount(account, 1), "money:write", true),
                new Endpoint(
                    "POST",
                    "/v1/transfers",
                    ("{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\","
                            + "\"amount\":1,\"currency\":\"EUR\"}")
                        .formatted(account, destination),
                    "money:write",
                    true),
                new Endpoint("GET", transactionPath, "", "transactions:read", false),
                new Endpoint(
                    "POST", transactionPath + "/reversal", "{}", "transactions:reverse", true)));
    endpoints.add(
        new Endpoint("POST", "/v1/holds", hold(account, 1, expiresAt), "holds:write", true));
    endpoints.add(new Endpoint("GET", "/v1/holds/" + holdId, "", "holds:read", false));
    endpoints.add(
        new Endpoint(
            "POST",
            "/v1/holds/" + holdId + "/capture",
            capture(1, destination),
            "holds:write",
            true));
    endpoints.add(
        new Endpoint("POST", "/v1/holds/" + holdId + "/release", "", "holds:write", true));

    int combinations = 0;
    for (Endpoint endpoint : endpoints) {
      for (Role role : Role.values()) {
        HttpRequest.Builder request = request(url(endpoint.path()), token(role));
        if (endpoint.idempotent()) {
          request.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        if (endpoint.method().equals("GET")) {
          request.GET();
        } else {
          request
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(endpoint.body()));
        }
        HttpResponse<String> response = send(request);
        assertThat(response.statusCode())
            .as("%s %s for %s: %s", endpoint.method(), endpoint.path(), role, response.body())
            .isNotEqualTo(401);
        if (role.scopes().contains(endpoint.scope())) {
          assertThat(response.statusCode())
              .as("%s %s for %s: %s", endpoint.method(), endpoint.path(), role, response.body())
              .isNotEqualTo(403);
        } else {
          assertThat(response.statusCode())
              .as("%s %s for %s: %s", endpoint.method(), endpoint.path(), role, response.body())
              .isEqualTo(403);
        }
        combinations++;
      }
    }
    assertThat(combinations).isEqualTo(75);
  }

  private UUID create() {
    HttpResponse<String> response =
        post("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"EUR\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private static String amount(UUID account, long amount) {
    return "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount);
  }

  private static String hold(UUID account, long amount, Instant expiresAt) {
    return ("{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"," + "\"expires_at\":\"%s\"}")
        .formatted(account, amount, expiresAt);
  }

  private static String capture(long amount, UUID destination) {
    return "{\"amount\":%d,\"destination_account_id\":\"%s\"}".formatted(amount, destination);
  }

  private record Endpoint(
      String method, String path, String body, String scope, boolean idempotent) {}
}
