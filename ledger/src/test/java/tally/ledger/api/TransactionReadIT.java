package tally.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TransactionReadIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void getTransactionReturnsEntriesInLineOrder() throws Exception {
    UUID source = create();
    UUID destination = create();
    assertThat(money("/v1/deposits", source, 500).statusCode()).isEqualTo(201);
    HttpResponse<String> posted = transfer(source, destination, 120);
    assertThat(posted.statusCode()).isEqualTo(201);
    JsonNode postedBody = JSON.readTree(posted.body());

    HttpResponse<String> response =
        get("/v1/transactions/" + postedBody.get("id").asString(), token(Role.SERVICE_DESK));

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body).isEqualTo(postedBody);
    assertThat(body.get("reversed_by_transaction_id").isNull()).isTrue();
    assertThat(body.get("entries").get(0).get("account_id").asString())
        .isEqualTo(source.toString());
    assertThat(body.get("entries").get(0).get("side").asString()).isEqualTo("DEBIT");
    assertThat(body.get("entries").get(1).get("account_id").asString())
        .isEqualTo(destination.toString());
    assertThat(body.get("entries").get(1).get("side").asString()).isEqualTo("CREDIT");
    assertThat(body.get("entries").get(0).get("id").asLong())
        .isLessThan(body.get("entries").get(1).get("id").asLong());
  }

  @Test
  void unknownTransactionIsNotFound() throws Exception {
    assertError(
        get("/v1/transactions/" + UUID.randomUUID(), token(Role.SERVICE_DESK)), 404, "NOT_FOUND");
  }

  @Test
  void requiresTransactionsReadScope() throws Exception {
    UUID source = create();
    UUID destination = create();
    assertThat(money("/v1/deposits", source, 500).statusCode()).isEqualTo(201);
    JsonNode posted = JSON.readTree(transfer(source, destination, 120).body());
    String restricted = ISSUER.tokenWithScopes("transaction-read-check", "accounts:read");

    assertError(
        get("/v1/transactions/" + posted.get("id").asString(), restricted), 403, "FORBIDDEN");
  }

  private UUID create() {
    HttpResponse<String> response =
        post("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"EUR\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private HttpResponse<String> money(String path, UUID account, long amount) {
    String body =
        "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount);
    return post(path, token(Role.OPERATOR), body, "Idempotency-Key", UUID.randomUUID().toString());
  }

  private HttpResponse<String> transfer(UUID source, UUID destination, long amount) {
    String body =
        ("{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\","
                + "\"amount\":%d,\"currency\":\"EUR\"}")
            .formatted(source, destination, amount);
    return post(
        "/v1/transfers",
        token(Role.OPERATOR),
        body,
        "Idempotency-Key",
        UUID.randomUUID().toString());
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(JSON.readTree(response.body()).get("error").get("code").asString()).isEqualTo(code);
  }
}
