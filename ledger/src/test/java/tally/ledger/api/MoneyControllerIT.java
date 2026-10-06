package tally.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MoneyControllerIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void depositReturnsTransactionShape() throws Exception {
    UUID customer = create();
    HttpResponse<String> response = money("/v1/deposits", amount(customer, 5000));

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("X-Tally-Attempts")).contains("1");
    assertThat(response.headers().firstValue("Idempotent-Replay")).contains("false");
    assertThat(response.headers().firstValue("Content-Type").orElseThrow())
        .startsWith("application/json");
    JsonNode tx = JSON.readTree(response.body());
    assertThat(tx.size()).isEqualTo(9);
    assertThat(UUID.fromString(tx.get("id").asString()).version()).isEqualTo(7);
    assertThat(tx.get("kind").asString()).isEqualTo("DEPOSIT");
    assertThat(tx.get("currency").asString()).isEqualTo("EUR");
    assertThat(tx.get("amount").isIntegralNumber()).isTrue();
    assertThat(tx.get("amount").asLong()).isEqualTo(5000);
    assertThat(tx.get("description").isNull()).isTrue();
    assertThat(tx.get("posted_at").asString())
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
    assertThat(tx.get("reverses_transaction_id").isNull()).isTrue();
    assertThat(tx.get("reversed_by_transaction_id").isNull()).isTrue();
    JsonNode entries = tx.get("entries");
    assertThat(entries.size()).isEqualTo(2);
    for (JsonNode entry : entries) {
      assertThat(entry.size()).isEqualTo(4);
      assertThat(entry.get("id").isIntegralNumber()).isTrue();
      assertThat(entry.get("id").asLong()).isPositive();
      assertThat(entry.get("amount").asLong()).isEqualTo(5000);
    }
    assertThat(entries.get(0).get("account_id").asString()).isEqualTo(customer.toString());
    assertThat(entries.get(0).get("side").asString()).isEqualTo("CREDIT");
    assertThat(entries.get(1).get("side").asString()).isEqualTo("DEBIT");
    assertThat(entries.get(1).get("id").asLong()).isGreaterThan(entries.get(0).get("id").asLong());
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id FROM ledger.account WHERE kind = 'SETTLEMENT' AND currency = 'EUR'");
        ResultSet row = select.executeQuery()) {
      assertThat(row.next()).isTrue();
      assertThat(entries.get(1).get("account_id").asString()).isEqualTo(row.getString("id"));
    }
    assertThat(balance(customer)).isEqualTo(5000);
  }

  @Test
  void withdrawalBelowLimitIsRejected() throws Exception {
    UUID customer = create();
    assertThat(money("/v1/deposits", amount(customer, 5000)).statusCode()).isEqualTo(201);

    assertError(money("/v1/withdrawals", amount(customer, 9000)), 422, "INSUFFICIENT_FUNDS");

    assertThat(balance(customer)).isEqualTo(5000);
  }

  @Test
  void transferBetweenCustomers() throws Exception {
    UUID source = create();
    UUID destination = create();
    assertThat(money("/v1/deposits", amount(source, 5000)).statusCode()).isEqualTo(201);
    String body =
        """
        {"source_account_id":"%s","destination_account_id":"%s","amount":700,
         "currency":"EUR","description":"rent"}
        """
            .formatted(source, destination);

    HttpResponse<String> response = money("/v1/transfers", body);

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode tx = JSON.readTree(response.body());
    assertThat(tx.get("kind").asString()).isEqualTo("TRANSFER");
    assertThat(tx.get("description").asString()).isEqualTo("rent");
    assertThat(tx.get("entries").get(0).get("account_id").asString()).isEqualTo(source.toString());
    assertThat(tx.get("entries").get(0).get("side").asString()).isEqualTo("DEBIT");
    assertThat(tx.get("entries").get(1).get("account_id").asString())
        .isEqualTo(destination.toString());
    assertThat(tx.get("entries").get(1).get("side").asString()).isEqualTo("CREDIT");
    assertThat(balance(source)).isEqualTo(4300);
    assertThat(balance(destination)).isEqualTo(700);
  }

  @Test
  void validationErrors() throws Exception {
    UUID customer = create();
    UUID destination = create();
    List<String> invalid =
        List.of(
            "{\"currency\":\"EUR\"}",
            "{\"amount\":0,\"currency\":\"EUR\"}",
            "{\"amount\":1,\"currency\":\"EUR\",\"description\":\"" + "x".repeat(201) + "\"}",
            "{\"amount\":1,\"currency\":\"XXQ\"}",
            "{\"amount\":1.5,\"currency\":\"EUR\"}");
    for (String path : List.of("/v1/deposits", "/v1/withdrawals", "/v1/transfers")) {
      for (int i = 0; i < invalid.size(); i++) {
        String ids =
            path.equals("/v1/transfers")
                ? "\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\","
                    .formatted(customer, destination)
                : "\"account_id\":\"%s\",".formatted(customer);
        String body = "{" + ids + invalid.get(i).substring(1);
        String key = UUID.randomUUID().toString();
        HttpResponse<String> response =
            post(path, token(Role.OPERATOR), body, "Idempotency-Key", key);
        assertError(response, 400, i == 4 ? "MALFORMED_REQUEST" : "VALIDATION_FAILED");
        if (i < 4) {
          String field = i < 2 ? "amount" : i == 2 ? "description" : "currency";
          assertThat(
                  JSON.readTree(response.body())
                      .get("error")
                      .get("details")
                      .get(0)
                      .get("field")
                      .asString())
              .isEqualTo(field);
        }
        try (Connection db = appJdbc();
            PreparedStatement select =
                db.prepareStatement(
                    "SELECT count(*) FROM ledger.idempotency_key WHERE client_id = ? AND key = ?")) {
          select.setString(1, "test-operator");
          select.setString(2, key);
          try (ResultSet row = select.executeQuery()) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong(1)).isZero();
          }
        }
      }
    }
  }

  @Test
  void requiresMoneyWriteScope() throws Exception {
    UUID customer = create();
    UUID destination = create();
    for (String path : List.of("/v1/deposits", "/v1/withdrawals", "/v1/transfers")) {
      String body =
          path.equals("/v1/transfers")
              ? """
                {"source_account_id":"%s","destination_account_id":"%s",
                 "amount":1,"currency":"EUR"}
                """
                  .formatted(customer, destination)
              : amount(customer, 1);
      for (Role role : List.of(Role.SERVICE_DESK, Role.AUDITOR)) {
        assertError(
            post(path, token(role), body, "Idempotency-Key", UUID.randomUUID().toString()),
            403,
            "FORBIDDEN");
      }
      assertError(
          post(path, null, body, "Idempotency-Key", UUID.randomUUID().toString()),
          401,
          "UNAUTHENTICATED");
    }
  }

  private UUID create() {
    HttpResponse<String> response =
        post("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"EUR\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private HttpResponse<String> money(String path, String body) {
    return post(path, token(Role.OPERATOR), body, "Idempotency-Key", UUID.randomUUID().toString());
  }

  private static String amount(UUID customer, long amount) {
    return "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(customer, amount);
  }

  private long balance(UUID account) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT balance FROM ledger.account WHERE id = ?")) {
      select.setObject(1, account);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(JSON.readTree(response.body()).get("error").get("code").asString()).isEqualTo(code);
  }
}
