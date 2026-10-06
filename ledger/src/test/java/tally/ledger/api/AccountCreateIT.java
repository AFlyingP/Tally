package tally.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tally.ledger.core.AuditHash;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AccountCreateIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter HASHED_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

  @Test
  void createsCustomerAccountWithDefaults() throws Exception {
    HttpResponse<String> response = create("{\"currency\":\"EUR\"}");

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode account = JSON.readTree(response.body());
    assertThat(account.get("kind").asString()).isEqualTo("CUSTOMER");
    assertThat(account.get("currency").asString()).isEqualTo("EUR");
    assertThat(account.get("normal_side").asString()).isEqualTo("CREDIT");
    assertThat(account.get("min_balance").isIntegralNumber()).isTrue();
    assertThat(account.get("min_balance").asLong()).isZero();
    assertThat(account.get("status").asString()).isEqualTo("OPEN");
    assertThat(account.get("created_at").asString())
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");

    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT balance, held, version FROM ledger.account WHERE id = ?")) {
      select.setObject(1, UUID.fromString(account.get("id").asString()));
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getLong("balance")).isZero();
        assertThat(row.getLong("held")).isZero();
        assertThat(row.getLong("version")).isZero();
      }
    }
  }

  @Test
  void createsInternalAccountsOncePerCurrency() throws Exception {
    assertThat(create("{\"currency\":\"CHF\"}").statusCode()).isEqualTo(201);
    assertThat(create("{\"currency\":\"CHF\"}").statusCode()).isEqualTo(201);

    try (Connection db = appJdbc();
        Statement select = db.createStatement();
        ResultSet rows =
            select.executeQuery(
                "SELECT kind, normal_side, min_balance FROM ledger.account"
                    + " WHERE currency = 'CHF' AND kind <> 'CUSTOMER' ORDER BY kind")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("kind")).isEqualTo("CLEARING");
      assertThat(rows.getString("normal_side")).isEqualTo("CREDIT");
      assertThat(rows.getObject("min_balance")).isNull();
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("kind")).isEqualTo("SETTLEMENT");
      assertThat(rows.getString("normal_side")).isEqualTo("DEBIT");
      assertThat(rows.getObject("min_balance")).isNull();
      assertThat(rows.next()).isFalse();
    }
  }

  @Test
  void writesOneAuditRowWithRecomputableHash() throws Exception {
    HttpResponse<String> response =
        post(
            "/v1/accounts",
            token(Role.OPERATOR),
            "{\"currency\":\"EUR\"}",
            "X-Request-Id",
            "test-req-0001");
    assertThat(response.statusCode()).isEqualTo(201);
    String accountId = JSON.readTree(response.body()).get("id").asString();

    try (Connection db = appJdbc();
        Statement select = db.createStatement();
        ResultSet row =
            select.executeQuery(
                "SELECT * FROM ledger.audit_log WHERE request_id = 'test-req-0001'")) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString("action")).isEqualTo("account.create");
      assertThat(row.getString("resource_type")).isEqualTo("account");
      assertThat(row.getString("resource_id")).isEqualTo(accountId);
      assertThat(row.getString("outcome")).isEqualTo("SUCCESS");
      assertThat(row.getString("actor")).isEqualTo("test-operator");
      assertThat(row.getString("actor_role")).isEqualTo("operator");
      assertThat(row.getString("scopes"))
          .isEqualTo(String.join(" ", Role.OPERATOR.scopes().stream().sorted().toList()));

      byte[] recomputed =
          AuditHash.row(
              row.getString("id"),
              HASHED_TIME.format(row.getObject("occurred_at", OffsetDateTime.class)),
              row.getString("actor"),
              row.getString("actor_role"),
              row.getString("scopes"),
              row.getString("action"),
              row.getString("resource_type"),
              row.getString("resource_id"),
              row.getString("request_id"),
              row.getString("outcome"),
              row.getString("reason"),
              row.getString("transaction_id"));
      assertThat(row.getBytes("row_hash")).isEqualTo(recomputed);
      assertThat(row.next()).isFalse();
    }
  }

  @Test
  void rejectsUnknownCurrency() {
    HttpResponse<String> response = create("{\"currency\":\"XXQ\"}");

    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode error = JSON.readTree(response.body()).get("error");
    assertThat(error.get("code").asString()).isEqualTo("VALIDATION_FAILED");
    assertThat(error.get("details").get(0).get("field").asString()).isEqualTo("currency");

    assertThat(create("{}").statusCode()).isEqualTo(400);
  }

  @Test
  void rejectsPositiveMinBalance() {
    HttpResponse<String> response = create("{\"currency\":\"EUR\",\"min_balance\":1}");

    assertThat(response.statusCode()).isEqualTo(400);
    JsonNode error = JSON.readTree(response.body()).get("error");
    assertThat(error.get("code").asString()).isEqualTo("VALIDATION_FAILED");
    assertThat(error.get("details").get(0).get("field").asString()).isEqualTo("min_balance");
  }

  @Test
  void acceptsDebitNormalSideAndNegativeLimit() {
    HttpResponse<String> response =
        create("{\"currency\":\"EUR\",\"normal_side\":\"DEBIT\",\"min_balance\":-5000}");

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode account = JSON.readTree(response.body());
    assertThat(account.get("normal_side").asString()).isEqualTo("DEBIT");
    assertThat(account.get("min_balance").asLong()).isEqualTo(-5000);
  }

  @Test
  void requiresAccountsWriteScope() {
    HttpResponse<String> forbidden =
        post("/v1/accounts", token(Role.SERVICE_DESK), "{\"currency\":\"EUR\"}");
    assertThat(forbidden.statusCode()).isEqualTo(403);
    assertThat(JSON.readTree(forbidden.body()).get("error").get("code").asString())
        .isEqualTo("FORBIDDEN");

    HttpResponse<String> anonymous = post("/v1/accounts", null, "{\"currency\":\"EUR\"}");
    assertThat(anonymous.statusCode()).isEqualTo(401);
  }

  @Test
  void auditTableRejectsUpdateAndDelete() throws Exception {
    assertThat(create("{\"currency\":\"EUR\"}").statusCode()).isEqualTo(201);

    try (Connection app = appJdbc();
        Statement statement = app.createStatement()) {
      assertThatThrownBy(() -> statement.executeUpdate("UPDATE ledger.audit_log SET actor = 'x'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM ledger.audit_log"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }
    try (Connection superuser = superJdbc();
        Statement statement = superuser.createStatement()) {
      assertThatThrownBy(() -> statement.executeUpdate("UPDATE ledger.audit_log SET actor = 'x'"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("TL001"));
      assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM ledger.audit_log"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("TL001"));
    }
  }

  @Test
  void rollsBackTheAccountWhenTheAuditRowFails() throws Exception {
    try (Connection superuser = superJdbc();
        Statement statement = superuser.createStatement()) {
      statement.execute(
          """
          CREATE FUNCTION ledger.fail_marked_audit() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN
            IF NEW.request_id = 'test-req-fail-0001' THEN
              RAISE EXCEPTION 'audit insert refused by the test';
            END IF;
            RETURN NEW;
          END $$;
          CREATE TRIGGER fail_marked_audit BEFORE INSERT ON ledger.audit_log
            FOR EACH ROW EXECUTE FUNCTION ledger.fail_marked_audit();
          """);
      try {
        HttpResponse<String> response =
            post(
                "/v1/accounts",
                token(Role.OPERATOR),
                "{\"currency\":\"JPY\"}",
                "X-Request-Id",
                "test-req-fail-0001");
        assertThat(response.statusCode()).isEqualTo(503);

        try (ResultSet accounts =
            statement.executeQuery("SELECT count(*) FROM ledger.account WHERE currency = 'JPY'")) {
          accounts.next();
          assertThat(accounts.getInt(1)).isZero();
        }
        try (ResultSet audit =
            statement.executeQuery(
                "SELECT count(*) FROM ledger.audit_log WHERE request_id = 'test-req-fail-0001'")) {
          audit.next();
          assertThat(audit.getInt(1)).isZero();
        }
      } finally {
        statement.execute(
            "DROP TRIGGER fail_marked_audit ON ledger.audit_log;"
                + " DROP FUNCTION ledger.fail_marked_audit()");
      }
    }
  }

  private HttpResponse<String> create(String json) {
    return post("/v1/accounts", token(Role.OPERATOR), json);
  }
}
