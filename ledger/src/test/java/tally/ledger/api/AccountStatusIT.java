package tally.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AccountStatusIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void freezeBlocksNewPostings() throws Exception {
    UUID account = create();
    UUID other = create();
    assertThat(money("/v1/deposits", amount(account, 10)).statusCode()).isEqualTo(201);
    assertThat(money("/v1/deposits", amount(other, 10)).statusCode()).isEqualTo(201);

    HttpResponse<String> frozen = freeze(account);

    assertThat(frozen.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(frozen.body()).get("status").asString()).isEqualTo("FROZEN");
    assertError(money("/v1/deposits", amount(account, 1)), 422, "ACCOUNT_FROZEN");
    assertError(money("/v1/withdrawals", amount(account, 1)), 422, "ACCOUNT_FROZEN");
    assertError(money("/v1/transfers", transfer(account, other, 1)), 422, "ACCOUNT_FROZEN");
    assertError(money("/v1/transfers", transfer(other, account, 1)), 422, "ACCOUNT_FROZEN");

    HttpResponse<String> balance = get("/v1/accounts/" + account + "/balance", token(Role.ADMIN));
    assertThat(balance.statusCode()).isEqualTo(200);
    JsonNode balanceBody = JSON.readTree(balance.body());
    assertThat(balanceBody.get("account_id").asString()).isEqualTo(account.toString());
    assertThat(balanceBody.get("balance").asLong()).isEqualTo(10);
  }

  @Test
  void freezeTwiceIsRejected() throws Exception {
    UUID account = create();
    assertThat(freeze(account).statusCode()).isEqualTo(200);

    assertError(freeze(account), 422, "ACCOUNT_FROZEN");
  }

  @Test
  void closeRequiresZeroBalance() throws Exception {
    UUID account = create();
    assertThat(money("/v1/deposits", amount(account, 10)).statusCode()).isEqualTo(201);

    assertError(close(account), 422, "ACCOUNT_NOT_EMPTY");
    assertThat(money("/v1/withdrawals", amount(account, 10)).statusCode()).isEqualTo(201);

    HttpResponse<String> closed = close(account);

    assertThat(closed.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(closed.body()).get("status").asString()).isEqualTo("CLOSED");
  }

  @Test
  void closeIsBlockedByOpenPayment() throws Exception {
    UUID account = create();
    HttpResponse<String> payment =
        post(
            "/v1/external-payments",
            token(Role.OPERATOR),
            "{\"source_account_id\":\"%s\",\"amount\":1,\"currency\":\"EUR\",\"creditor_account\":\"DEMO-OK\"}"
                .formatted(account),
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(payment.statusCode()).isEqualTo(202);

    assertError(close(account), 422, "ACCOUNT_NOT_EMPTY");
  }

  @Test
  void closedAccountRejectsEverything() throws Exception {
    UUID account = create();
    assertThat(close(account).statusCode()).isEqualTo(200);

    assertError(money("/v1/deposits", amount(account, 1)), 422, "ACCOUNT_CLOSED");
    assertError(freeze(account), 422, "ACCOUNT_CLOSED");
    assertError(close(account), 422, "ACCOUNT_CLOSED");
  }

  @Test
  void frozenAccountCanBeClosed() throws Exception {
    UUID account = create();
    assertThat(freeze(account).statusCode()).isEqualTo(200);

    HttpResponse<String> closed = close(account);

    assertThat(closed.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(closed.body()).get("status").asString()).isEqualTo("CLOSED");
  }

  @Test
  void internalAccountCannotBeFrozenOrClosed() throws Exception {
    create();
    UUID settlement = settlement("EUR");

    assertError(freeze(settlement), 422, "INTERNAL_ACCOUNT");
    assertError(close(settlement), 422, "INTERNAL_ACCOUNT");
  }

  @Test
  void unknownAccountIsNotFound() {
    UUID account = UUID.randomUUID();

    assertError(freeze(account), 404, "NOT_FOUND");
    assertError(close(account), 404, "NOT_FOUND");
  }

  @Test
  void statusChangeWritesOneAuditRowAndBumpsVersion() throws Exception {
    UUID account = create();
    long versionBefore = version(account);

    assertThat(freeze(account).statusCode()).isEqualTo(200);

    assertThat(auditCount(account, "account.freeze")).isEqualTo(1);
    assertThat(version(account)).isEqualTo(versionBefore + 1);
  }

  @Test
  void requiresAccountsAdminScope() throws Exception {
    UUID account = create();

    assertError(
        post("/v1/accounts/" + account + "/freeze", token(Role.OPERATOR), ""), 403, "FORBIDDEN");
    assertError(
        post("/v1/accounts/" + account + "/close", token(Role.OPERATOR), ""), 403, "FORBIDDEN");
    assertThat(freeze(account).statusCode()).isEqualTo(200);
    assertThat(close(account).statusCode()).isEqualTo(200);
  }

  private UUID create() {
    HttpResponse<String> response =
        post("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"EUR\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private HttpResponse<String> freeze(UUID account) {
    return post("/v1/accounts/" + account + "/freeze", token(Role.ADMIN), "");
  }

  private HttpResponse<String> close(UUID account) {
    return post("/v1/accounts/" + account + "/close", token(Role.ADMIN), "");
  }

  private HttpResponse<String> money(String path, String body) {
    return post(path, token(Role.OPERATOR), body, "Idempotency-Key", UUID.randomUUID().toString());
  }

  private UUID settlement(String currency) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id FROM ledger.account WHERE kind = 'SETTLEMENT' AND currency = ?")) {
      select.setString(1, currency);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getObject("id", UUID.class);
      }
    }
  }

  private long version(UUID account) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT version FROM ledger.account WHERE id = ?")) {
      select.setObject(1, account);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong("version");
      }
    }
  }

  private long auditCount(UUID account, String action) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.audit_log "
                    + "WHERE resource_type = 'account' AND resource_id = ? AND action = ?")) {
      select.setString(1, account.toString());
      select.setString(2, action);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private static String amount(UUID account, long amount) {
    return "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount);
  }

  private static String transfer(UUID source, UUID destination, long amount) {
    return ("{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\","
            + "\"amount\":%d,\"currency\":\"EUR\"}")
        .formatted(source, destination, amount);
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(JSON.readTree(response.body()).get("error").get("code").asString()).isEqualTo(code);
  }
}
