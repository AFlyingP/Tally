package tally.ledger.payments;

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

class PaymentApiIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void createReturns202Initiated() throws Exception {
    UUID account = create();

    HttpResponse<String> response = createPayment(account, 120, key());

    assertThat(response.statusCode()).isEqualTo(202);
    JsonNode body = JSON.readTree(response.body());
    UUID id = UUID.fromString(body.get("id").asString());
    assertThat(body.get("state").asString()).isEqualTo("INITIATED");
    assertThat(body.get("external_reference").asString()).matches("TLY-[A-F0-9]{32}");
    assertThat(body.get("events").size()).isEqualTo(1);
    assertThat(body.get("events").get(0).get("from_state").isNull()).isTrue();
    assertThat(body.get("events").get(0).get("to_state").asString()).isEqualTo("INITIATED");
    assertThat(auditCount(id)).isEqualTo(1);
    assertThat(auditAction(id)).isEqualTo("payment.create");
  }

  @Test
  void createIsIdempotent() throws Exception {
    UUID account = create();
    String key = key();

    HttpResponse<String> first = createPayment(account, 120, key);
    HttpResponse<String> replay = createPayment(account, 120, key);

    assertThat(first.statusCode()).isEqualTo(202);
    assertThat(replay.statusCode()).isEqualTo(202);
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
    UUID id = UUID.fromString(JSON.readTree(first.body()).get("id").asString());
    assertThat(paymentCountForSource(account)).isEqualTo(1);
    assertThat(eventCount(id)).isEqualTo(1);
    assertThat(auditCount(id)).isEqualTo(1);
  }

  @Test
  void createValidation() throws Exception {
    UUID account = create();
    String badCreditorKey = key();
    assertError(createPayment(account, 10, "ab", badCreditorKey), 400, "VALIDATION_FAILED");
    assertThat(idempotencyKeyCount(badCreditorKey)).isZero();
    String badAmountKey = key();
    assertError(createPayment(account, 0, "DEMO-OK", badAmountKey), 400, "VALIDATION_FAILED");
    assertThat(idempotencyKeyCount(badAmountKey)).isZero();

    assertError(createPayment(UUID.randomUUID(), 10, key()), 404, "NOT_FOUND");
    assertError(createPayment(settlement(), 10, key()), 422, "INTERNAL_ACCOUNT");
    UUID frozen = create();
    assertThat(post("/v1/accounts/" + frozen + "/freeze", token(Role.ADMIN), "").statusCode())
        .isEqualTo(200);
    assertError(createPayment(frozen, 10, key()), 422, "ACCOUNT_FROZEN");
    assertError(createPayment(account, 10, "DEMO-OK", "USD", key()), 422, "CURRENCY_MISMATCH");
  }

  @Test
  void getUnknownIs404() {
    assertError(
        get("/v1/external-payments/" + UUID.randomUUID(), token(Role.OPERATOR)), 404, "NOT_FOUND");
  }

  private UUID create() {
    HttpResponse<String> response =
        post("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"EUR\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private HttpResponse<String> createPayment(UUID account, long amount, String key) {
    return createPayment(account, amount, "DEMO-OK", "EUR", key);
  }

  private HttpResponse<String> createPayment(
      UUID account, long amount, String creditor, String key) {
    return createPayment(account, amount, creditor, "EUR", key);
  }

  private HttpResponse<String> createPayment(
      UUID account, long amount, String creditor, String currency, String key) {
    return post(
        "/v1/external-payments",
        token(Role.OPERATOR),
        payment(account, amount, creditor, currency),
        "Idempotency-Key",
        key);
  }

  private UUID settlement() throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id FROM ledger.account WHERE kind = 'SETTLEMENT' AND currency = 'EUR'")) {
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getObject("id", UUID.class);
      }
    }
  }

  private long paymentCountForSource(UUID source) throws Exception {
    return count(
        "SELECT count(*) FROM ledger.external_payment WHERE source_account_id = ?", source);
  }

  private long eventCount(UUID id) throws Exception {
    return count("SELECT count(*) FROM ledger.payment_event WHERE payment_id = ?", id);
  }

  private long auditCount(UUID id) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.audit_log WHERE resource_type = 'external_payment'"
                    + " AND resource_id = ? AND action = 'payment.create'")) {
      select.setString(1, id.toString());
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private String auditAction(UUID id) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT action FROM ledger.audit_log WHERE resource_type = 'external_payment'"
                    + " AND resource_id = ?")) {
      select.setString(1, id.toString());
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getString(1);
      }
    }
  }

  private long idempotencyKeyCount(String key) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.idempotency_key WHERE client_id = 'test-operator'"
                    + " AND key = ?")) {
      select.setString(1, key);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private long count(String sql, UUID id) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select = db.prepareStatement(sql)) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private static String payment(UUID account, long amount, String creditor, String currency) {
    return "{\"source_account_id\":\"%s\",\"amount\":%d,\"currency\":\"%s\",\"creditor_account\":\"%s\"}"
        .formatted(account, amount, currency, creditor);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(JSON.readTree(response.body()).get("error").get("code").asString()).isEqualTo(code);
  }
}
