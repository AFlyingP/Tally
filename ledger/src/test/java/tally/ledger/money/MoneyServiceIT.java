package tally.ledger.money;

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
import tools.jackson.databind.json.JsonMapper;

class MoneyServiceIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void depositToInternalAccountIsRejected() throws Exception {
    create("EUR");
    UUID settlement;
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id FROM ledger.account WHERE kind = 'SETTLEMENT' AND currency = 'EUR'");
        ResultSet row = select.executeQuery()) {
      assertThat(row.next()).isTrue();
      settlement = row.getObject("id", UUID.class);
    }

    assertError(money("/v1/deposits", amount(settlement, 1)), 422, "INTERNAL_ACCOUNT");
  }

  @Test
  void transferToSameAccountIsRejected() throws Exception {
    UUID customer = create("EUR");

    assertError(money("/v1/transfers", transfer(customer, customer, "EUR")), 422, "SAME_ACCOUNT");
  }

  @Test
  void transferAcrossCurrenciesIsRejected() throws Exception {
    UUID eur = create("EUR");
    UUID usd = create("USD");
    UUID otherEur = create("EUR");

    assertError(money("/v1/transfers", transfer(eur, usd, "EUR")), 422, "CURRENCY_MISMATCH");
    assertError(money("/v1/transfers", transfer(eur, otherEur, "USD")), 422, "CURRENCY_MISMATCH");
  }

  @Test
  void unknownAccountIsNotFound() throws Exception {
    UUID source = create("EUR");
    String body = transfer(source, UUID.randomUUID(), "EUR");
    String key = UUID.randomUUID().toString();

    HttpResponse<String> first = money("/v1/transfers", body, key);
    HttpResponse<String> replay = money("/v1/transfers", body, key);

    assertError(first, 404, "NOT_FOUND");
    assertError(replay, 404, "NOT_FOUND");
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(first.headers().firstValue("Idempotent-Replay")).contains("false");
    assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT status_code, response_body FROM ledger.idempotency_key"
                    + " WHERE client_id = 'test-operator' AND key = ?")) {
      select.setString(1, key);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getInt("status_code")).isEqualTo(404);
        assertThat(row.getString("response_body")).isEqualTo(first.body());
        assertThat(row.next()).isFalse();
      }
    }
  }

  @Test
  void everyPostingHasOneAuditRow() throws Exception {
    UUID source = create("EUR");
    UUID destination = create("EUR");
    List<String> paths = List.of("/v1/deposits", "/v1/withdrawals", "/v1/transfers");
    List<String> bodies =
        List.of(amount(source, 100), amount(source, 1), transfer(source, destination, "EUR"));
    List<String> actions = List.of("deposit.post", "withdrawal.post", "transfer.post");
    for (int i = 0; i < paths.size(); i++) {
      String key = UUID.randomUUID().toString();
      HttpResponse<String> response = money(paths.get(i), bodies.get(i), key);
      assertThat(response.statusCode()).isEqualTo(201);
      UUID id = UUID.fromString(JSON.readTree(response.body()).get("id").asString());
      HttpResponse<String> replay = money(paths.get(i), bodies.get(i), key);
      assertThat(replay.statusCode()).isEqualTo(201);
      assertThat(replay.body()).isEqualTo(response.body());
      assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
      try (Connection db = appJdbc();
          PreparedStatement select =
              db.prepareStatement("SELECT * FROM ledger.audit_log WHERE transaction_id = ?")) {
        select.setObject(1, id);
        try (ResultSet row = select.executeQuery()) {
          assertThat(row.next()).isTrue();
          assertThat(row.getString("action")).isEqualTo(actions.get(i));
          assertThat(row.getString("resource_type")).isEqualTo("transaction");
          assertThat(row.getString("resource_id")).isEqualTo(id.toString());
          assertThat(row.getObject("transaction_id", UUID.class)).isEqualTo(id);
          assertThat(row.next()).isFalse();
        }
      }
    }

    List<String> rejected =
        List.of(
            transfer(source, source, "EUR"),
            transfer(source, UUID.randomUUID(), "EUR"),
            transfer(source, destination, "USD"),
            transfer(destination, source, "EUR").replace("\"amount\":1", "\"amount\":1000"));
    List<String> codes =
        List.of("SAME_ACCOUNT", "NOT_FOUND", "CURRENCY_MISMATCH", "INSUFFICIENT_FUNDS");
    for (int i = 0; i < rejected.size(); i++) {
      HttpResponse<String> response = money("/v1/transfers", rejected.get(i));
      assertError(response, i == 1 ? 404 : 422, codes.get(i));
    }
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.audit_log a JOIN ledger.ledger_transaction t"
                    + " ON t.id = a.transaction_id WHERE t.id IN"
                    + " (SELECT transaction_id FROM ledger.entry WHERE account_id IN (?, ?))")) {
      select.setObject(1, source);
      select.setObject(2, destination);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getLong(1)).isEqualTo(3);
      }
    }
  }

  private UUID create(String currency) {
    HttpResponse<String> response =
        post("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"%s\"}".formatted(currency));
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private HttpResponse<String> money(String path, String body) {
    return money(path, body, UUID.randomUUID().toString());
  }

  private HttpResponse<String> money(String path, String body, String key) {
    return post(path, token(Role.OPERATOR), body, "Idempotency-Key", key);
  }

  private static String amount(UUID customer, long amount) {
    return "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(customer, amount);
  }

  private static String transfer(UUID source, UUID destination, String currency) {
    return """
        {"source_account_id":"%s","destination_account_id":"%s","amount":1,"currency":"%s"}
        """
        .formatted(source, destination, currency);
  }

  private void assertError(HttpResponse<String> response, int status, String code)
      throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(JSON.readTree(response.body()).get("error").get("code").asString()).isEqualTo(code);
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT count(*) FROM ledger.audit_log WHERE request_id = ?")) {
      select.setString(1, response.headers().firstValue("X-Request-Id").orElseThrow());
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getLong(1)).isZero();
      }
    }
  }
}
