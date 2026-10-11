package tally.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tally.ledger.LedgerApplication;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tally.platform.testing.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AccountReadIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void getAccountReturnsShape() throws Exception {
    UUID account = create("EUR");

    HttpResponse<String> response = get("/v1/accounts/" + account, token(Role.SERVICE_DESK));

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.size()).isEqualTo(7);
    assertThat(body.get("id").asString()).isEqualTo(account.toString());
    assertThat(body.get("kind").asString()).isEqualTo("CUSTOMER");
    assertThat(body.get("currency").asString()).isEqualTo("EUR");
    assertThat(body.get("normal_side").asString()).isEqualTo("CREDIT");
    assertThat(body.get("min_balance").asLong()).isZero();
    assertThat(body.get("status").asString()).isEqualTo("OPEN");
    assertThat(body.get("created_at").asString())
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");

    assertError(
        get("/v1/accounts/" + UUID.randomUUID(), token(Role.SERVICE_DESK)), 404, "NOT_FOUND");
    assertError(get("/v1/accounts/not-a-uuid", token(Role.SERVICE_DESK)), 400, "VALIDATION_FAILED");
  }

  @Test
  void getInternalAccountWorks() throws Exception {
    create("EUR");
    UUID settlement = settlement("EUR");

    HttpResponse<String> response = get("/v1/accounts/" + settlement, token(Role.SERVICE_DESK));

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("id").asString()).isEqualTo(settlement.toString());
    assertThat(body.get("kind").asString()).isEqualTo("SETTLEMENT");
    assertThat(body.get("min_balance").isNull()).isTrue();
  }

  @Test
  void currentBalanceReturnsStoredValues() throws Exception {
    UUID account = create("EUR");
    assertThat(money("/v1/deposits", account, 500).statusCode()).isEqualTo(201);
    assertThat(money("/v1/withdrawals", account, 120).statusCode()).isEqualTo(201);

    HttpResponse<String> response =
        get("/v1/accounts/" + account + "/balance", token(Role.SERVICE_DESK));

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("account_id").asString()).isEqualTo(account.toString());
    assertThat(body.get("currency").asString()).isEqualTo("EUR");
    assertThat(body.get("balance").asLong()).isEqualTo(380);
    assertThat(body.get("held").asLong()).isZero();
    assertThat(body.get("available").asLong()).isEqualTo(380);
    assertThat(body.get("as_of").asString())
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
  }

  @Test
  void asOfBalanceIsComputedFromEntries() throws Exception {
    Instant t1 = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);
    MutableClock clock = new MutableClock(t1);
    try (ConfigurableApplicationContext context =
        LedgerApplication.builder()
            .initializers(
                initializer ->
                    ((GenericApplicationContext) initializer)
                        .registerBean(Clock.class, () -> clock))
            .run(args(Map.of()))) {
      UUID account = create(context, "EUR");
      assertThat(money(context, "/v1/deposits", account, 500).statusCode()).isEqualTo(201);

      Instant t2 = t1.plusSeconds(60);
      clock.set(t2);
      assertThat(money(context, "/v1/withdrawals", account, 120).statusCode()).isEqualTo(201);
      clock.set(t2.plusSeconds(60));

      JsonNode atT1 =
          JSON.readTree(get(context, balancePath(account, t1), token(Role.SERVICE_DESK)).body());
      assertThat(atT1.get("balance").asLong()).isEqualTo(500);
      assertThat(atT1.get("held").isNull()).isTrue();
      assertThat(atT1.get("available").isNull()).isTrue();
      assertThat(Instant.parse(atT1.get("as_of").asString())).isEqualTo(t1);

      JsonNode atT2 =
          JSON.readTree(get(context, balancePath(account, t2), token(Role.SERVICE_DESK)).body());
      assertThat(atT2.get("balance").asLong()).isEqualTo(380);

      JsonNode beforeAccount =
          JSON.readTree(
              get(context, balancePath(account, t1.minusSeconds(1)), token(Role.SERVICE_DESK))
                  .body());
      assertThat(beforeAccount.get("balance").asLong()).isZero();

      Instant future = clock.instant().plusSeconds(3600);
      assertError(
          get(context, balancePath(account, future), token(Role.SERVICE_DESK)),
          400,
          "VALIDATION_FAILED");
      assertError(
          get(
              context,
              "/v1/accounts/" + account + "/balance?as_of=yesterday",
              token(Role.SERVICE_DESK)),
          400,
          "VALIDATION_FAILED");
    }
  }

  @Test
  void asOfBalanceOfDebitNormalAccount() throws Exception {
    UUID customer = create("SEK");
    UUID settlement = settlement("SEK");
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT count(*) FROM ledger.entry WHERE account_id = ?")) {
      select.setObject(1, settlement);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getLong(1)).isZero();
      }
    }
    HttpResponse<String> deposited = money("/v1/deposits", customer, 500, "SEK");
    assertThat(deposited.statusCode()).isEqualTo(201);
    String postedAt = JSON.readTree(deposited.body()).get("posted_at").asString();

    HttpResponse<String> response =
        get(
            "/v1/accounts/" + settlement + "/balance?as_of=" + encode(postedAt),
            token(Role.SERVICE_DESK));

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("balance").asLong()).isEqualTo(500);
    assertThat(body.get("held").isNull()).isTrue();
    assertThat(body.get("available").isNull()).isTrue();
  }

  @Test
  void entriesArePagedInAscendingIdOrder() throws Exception {
    UUID account = create("EUR");
    List<Long> createdIds = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      HttpResponse<String> deposited = money("/v1/deposits", account, 1);
      assertThat(deposited.statusCode()).isEqualTo(201);
      JsonNode transactionEntries = JSON.readTree(deposited.body()).get("entries");
      List<Long> accountEntryIds = new ArrayList<>();
      for (JsonNode entry : transactionEntries) {
        if (entry.get("account_id").asString().equals(account.toString())) {
          accountEntryIds.add(entry.get("id").asLong());
        }
      }
      assertThat(accountEntryIds).hasSize(1);
      createdIds.add(accountEntryIds.get(0));
    }

    List<Long> receivedIds = new ArrayList<>();
    String path = "/v1/accounts/" + account + "/entries?limit=2";
    JsonNode first = JSON.readTree(get(path, token(Role.SERVICE_DESK)).body());
    assertThat(first.get("items").size()).isEqualTo(2);
    assertThat(first.get("next_cursor").isString()).isTrue();
    appendIds(first, receivedIds);

    JsonNode second =
        JSON.readTree(
            get(
                    path + "&cursor=" + encode(first.get("next_cursor").asString()),
                    token(Role.SERVICE_DESK))
                .body());
    assertThat(second.get("items").size()).isEqualTo(2);
    assertThat(second.get("next_cursor").isString()).isTrue();
    appendIds(second, receivedIds);

    JsonNode third =
        JSON.readTree(
            get(
                    path + "&cursor=" + encode(second.get("next_cursor").asString()),
                    token(Role.SERVICE_DESK))
                .body());
    assertThat(third.get("items").size()).isEqualTo(1);
    assertThat(third.get("next_cursor").isNull()).isTrue();
    appendIds(third, receivedIds);

    assertThat(receivedIds).hasSize(5).containsExactlyElementsOf(createdIds);
    for (int i = 1; i < receivedIds.size(); i++) {
      assertThat(receivedIds.get(i)).isGreaterThan(receivedIds.get(i - 1));
    }
  }

  @Test
  void entriesRejectBadLimitAndCursor() throws Exception {
    UUID account = create("EUR");
    String path = "/v1/accounts/" + account + "/entries";

    assertError(get(path + "?limit=0", token(Role.SERVICE_DESK)), 400, "VALIDATION_FAILED");
    assertError(get(path + "?limit=201", token(Role.SERVICE_DESK)), 400, "VALIDATION_FAILED");
    assertError(
        get(path + "?cursor=%2A%2A%2A", token(Role.SERVICE_DESK)), 400, "VALIDATION_FAILED");
  }

  @Test
  void readsRequireAccountsReadScope() throws Exception {
    UUID account = create("EUR");
    String restricted = ISSUER.tokenWithScopes("account-read-check", "transactions:read");
    List<String> paths =
        List.of(
            "/v1/accounts/" + account,
            "/v1/accounts/" + account + "/balance",
            "/v1/accounts/" + account + "/entries");
    for (String path : paths) {
      assertError(get(path, restricted), 403, "FORBIDDEN");
    }
    assertThat(Role.values()).hasSize(5);
    for (Role role : Role.values()) {
      for (String path : paths) {
        assertThat(get(path, token(role)).statusCode()).isEqualTo(200);
      }
    }
  }

  private UUID create(String currency) {
    HttpResponse<String> response =
        post("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"" + currency + "\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private UUID create(ConfigurableApplicationContext context, String currency) {
    HttpResponse<String> response =
        json(context, "/v1/accounts", "{\"currency\":\"" + currency + "\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private HttpResponse<String> money(String path, UUID account, long amount) {
    return money(path, account, amount, "EUR");
  }

  private HttpResponse<String> money(String path, UUID account, long amount, String currency) {
    return postMoney(url(path), account, amount, currency);
  }

  private HttpResponse<String> money(
      ConfigurableApplicationContext context, String path, UUID account, long amount) {
    return postMoney(url(context, path), account, amount, "EUR");
  }

  private HttpResponse<String> postMoney(String url, UUID account, long amount, String currency) {
    return send(
        request(url, token(Role.OPERATOR))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"%s\"}"
                        .formatted(account, amount, currency))));
  }

  private HttpResponse<String> json(
      ConfigurableApplicationContext context, String path, String body) {
    return send(
        request(url(context, path), token(Role.OPERATOR))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .POST(HttpRequest.BodyPublishers.ofString(body)));
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

  private static String balancePath(UUID account, Instant asOf) {
    return "/v1/accounts/" + account + "/balance?as_of=" + encode(asOf.toString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static void appendIds(JsonNode page, List<Long> ids) {
    for (JsonNode item : page.get("items")) {
      ids.add(item.get("id").asLong());
    }
  }

  private HttpResponse<String> get(
      ConfigurableApplicationContext context, String path, String accessToken) {
    return send(request(url(context, path), accessToken).GET());
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(JSON.readTree(response.body()).get("error").get("code").asString()).isEqualTo(code);
  }
}
