package tally.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    Instant scheduleStart = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
    HttpResponse<String> scheduled =
        post(
            "/v1/schedules",
            token(Role.OPERATOR),
            schedule(account, destination, scheduleStart),
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(scheduled.statusCode()).isEqualTo(201);
    UUID scheduleId = UUID.fromString(JSON.readTree(scheduled.body()).get("id").asString());
    HttpResponse<String> createdPayment =
        post(
            "/v1/external-payments",
            token(Role.OPERATOR),
            payment(account),
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(createdPayment.statusCode()).isEqualTo(202);
    UUID paymentId = UUID.fromString(JSON.readTree(createdPayment.body()).get("id").asString());
    String reconBody =
        JSON.writeValueAsString(
            Map.of(
                "business_date",
                "2026-02-01",
                "statement_csv",
                "reference,creditor_account,amount,currency,value_date\n"));
    HttpResponse<String> reconciled = post("/v1/reconciliation/runs", token(Role.ADMIN), reconBody);
    assertThat(reconciled.statusCode()).isIn(200, 201);
    UUID runId = UUID.fromString(JSON.readTree(reconciled.body()).get("id").asString());
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
    endpoints.add(
        new Endpoint(
            "POST",
            "/v1/schedules",
            schedule(account, destination, scheduleStart),
            "schedules:write",
            true));
    endpoints.add(new Endpoint("GET", "/v1/schedules/" + scheduleId, "", "schedules:read", false));
    endpoints.add(
        new Endpoint(
            "GET", "/v1/schedules/" + scheduleId + "/occurrences", "", "schedules:read", false));
    endpoints.add(
        new Endpoint(
            "POST", "/v1/schedules/" + scheduleId + "/cancel", "", "schedules:write", false));
    endpoints.add(
        new Endpoint("POST", "/v1/external-payments", payment(account), "payments:write", true));
    endpoints.add(
        new Endpoint("GET", "/v1/external-payments/" + paymentId, "", "payments:read", false));
    endpoints.add(new Endpoint("POST", "/v1/reconciliation/runs", reconBody, "recon:run", false));
    endpoints.add(
        new Endpoint(
            "GET", "/v1/reconciliation/runs?business_date=2026-02-01", "", "recon:read", false));
    endpoints.add(new Endpoint("GET", "/v1/reconciliation/runs/" + runId, "", "recon:read", false));
    endpoints.add(new Endpoint("GET", "/v1/audit", "", "audit:read", false));

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
    assertThat(combinations).isEqualTo(125);
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

  private static String schedule(UUID source, UUID destination, Instant startAt) {
    return "{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\",\"amount\":1,\"currency\":\"EUR\",\"frequency\":\"ONCE\",\"start_at\":\"%s\"}"
        .formatted(source, destination, startAt);
  }

  private static String payment(UUID source) {
    return "{\"source_account_id\":\"%s\",\"amount\":1,\"currency\":\"EUR\",\"creditor_account\":\"DEMO-OK\"}"
        .formatted(source);
  }

  private record Endpoint(
      String method, String path, String body, String scope, boolean idempotent) {}
}
