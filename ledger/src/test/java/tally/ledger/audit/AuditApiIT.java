package tally.ledger.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tally.ledger.LedgerApplication;
import tally.ledger.api.Cursor;
import tally.ledger.holds.HoldService;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tally.platform.testing.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditApiIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant START = Instant.parse("2031-04-01T12:00:00Z");

  private final MutableClock clock = new MutableClock(START);
  private ConfigurableApplicationContext context;
  private Instant start;
  private int testDay;

  @BeforeAll
  void start() {
    context =
        LedgerApplication.builder()
            .initializers(
                initializer ->
                    ((GenericApplicationContext) initializer)
                        .registerBean(Clock.class, () -> clock))
            .run(args(Map.of()));
  }

  @BeforeEach
  void setClock() {
    start = START.plus(Duration.ofDays(testDay++));
    clock.set(start);
  }

  @AfterAll
  void stop() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void trailOfAnAccountListsItsChangesInOrder() {
    UUID account = create();
    clock.advance(Duration.ofSeconds(1));
    postJson("/v1/accounts/" + account + "/freeze", "", 200);
    clock.advance(Duration.ofSeconds(1));
    postJson("/v1/accounts/" + account + "/close", "", 200);

    JsonNode page = audit(resource("account", account));
    List<JsonNode> rows = items(page);
    assertThat(rows).hasSize(3);
    assertThat(rows)
        .extracting(row -> text(row, "action"))
        .containsExactly("account.create", "account.freeze", "account.close");
    assertThat(rows).extracting(row -> text(row, "occurred_at")).isSorted();
    for (JsonNode row : rows) {
      assertThat(text(row, "resource_type")).isEqualTo("account");
      assertThat(text(row, "resource_id")).isEqualTo(account.toString());
      assertThat(text(row, "outcome")).isEqualTo("SUCCESS");
      assertThat(row.get("reason").isNull()).isTrue();
      assertThat(row.get("transaction_id").isNull()).isTrue();
    }
    assertThat(page.get("next_cursor").isNull()).isTrue();
  }

  @Test
  void trailOfATransaction() {
    UUID account = create();
    JsonNode deposit = postJson("/v1/deposits", amount(account, 100), 201);
    UUID transaction = id(deposit);

    List<JsonNode> rows = items(audit(resource("transaction", transaction)));
    assertThat(rows).hasSize(1);
    assertThat(text(rows.getFirst(), "action")).isEqualTo("deposit.post");
    assertThat(text(rows.getFirst(), "transaction_id")).isEqualTo(transaction.toString());
    assertThat(text(rows.getFirst(), "resource_id")).isEqualTo(transaction.toString());
  }

  @Test
  void timeRangeIsHalfOpen() {
    Instant t1 = clock.instant();
    UUID account = create();
    Instant t2 = t1.plusSeconds(1);
    clock.set(t2);
    postJson("/v1/accounts/" + account + "/freeze", "", 200);
    Instant t3 = t2.plusSeconds(1);
    clock.set(t3);
    postJson("/v1/accounts/" + account + "/close", "", 200);

    String filter = resource("account", account);
    List<JsonNode> rows = items(audit(filter + "&from=" + t1 + "&to=" + t3));
    assertThat(rows)
        .extracting(row -> Instant.parse(text(row, "occurred_at")))
        .containsExactly(t1, t2);
    assertThat(rows)
        .extracting(row -> text(row, "action"))
        .containsExactly("account.create", "account.freeze");
    assertThat(items(audit(filter + "&from=" + t3)))
        .extracting(row -> text(row, "action"))
        .containsExactly("account.close");
    assertThat(items(audit(filter + "&to=" + t2)))
        .extracting(row -> text(row, "action"))
        .containsExactly("account.create");
    assertThat(items(audit(filter + "&from=" + t2 + "&to=" + t2))).isEmpty();
  }

  @Test
  void pagingReturnsEveryRowOnce() {
    List<String> accounts = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      accounts.add(create().toString());
    }
    String filter = window() + "&limit=3";
    List<JsonNode> rows = new ArrayList<>();
    String cursor = null;
    for (int size : List.of(3, 3, 1)) {
      JsonNode page = audit(filter + (cursor == null ? "" : "&cursor=" + cursor));
      List<JsonNode> batch = items(page);
      assertThat(batch).hasSize(size);
      rows.addAll(batch);
      if (size == 1) {
        assertThat(page.get("next_cursor").isNull()).isTrue();
      } else {
        cursor = text(page, "next_cursor");
        JsonNode last = batch.getLast();
        assertThat(Cursor.decode(cursor))
            .isEqualTo(Instant.parse(text(last, "occurred_at")) + "|" + text(last, "id"));
      }
    }
    assertThat(rows).extracting(row -> text(row, "id")).doesNotHaveDuplicates().isSorted();
    assertThat(rows)
        .extracting(row -> text(row, "resource_id"))
        .containsExactlyInAnyOrderElementsOf(accounts);
    assertThat(rows).extracting(row -> Instant.parse(text(row, "occurred_at"))).containsOnly(start);
    assertThat(items(audit(resource("account", UUID.randomUUID())))).isEmpty();
  }

  @Test
  void deniedRequestIsAudited() {
    String clientId = "desk-" + UUID.randomUUID();
    String forbiddenId = UUID.randomUUID().toString();
    String anonymousId = UUID.randomUUID().toString();
    String body = amount(UUID.randomUUID(), 1);
    HttpResponse<String> forbidden =
        api(
            "POST",
            "/v1/deposits",
            ISSUER.token(clientId, Role.SERVICE_DESK),
            body,
            "X-Request-Id",
            forbiddenId);
    assertThat(forbidden.statusCode()).isEqualTo(403);
    HttpResponse<String> anonymous =
        api("POST", "/v1/deposits", null, body, "X-Request-Id", anonymousId);
    assertThat(anonymous.statusCode()).isEqualTo(401);

    List<JsonNode> rows =
        items(audit("resource_type=request&resource_id=POST%20/v1/deposits&" + window()));
    List<JsonNode> forbiddenRows =
        rows.stream().filter(row -> text(row, "request_id").equals(forbiddenId)).toList();
    assertThat(forbiddenRows).hasSize(1);
    JsonNode denied = forbiddenRows.getFirst();
    assertDenied(denied, clientId, "FORBIDDEN", forbiddenId);
    assertThat(text(denied, "actor_role")).isEqualTo("service-desk");
    assertThat(text(denied, "scopes"))
        .isEqualTo(String.join(" ", Role.SERVICE_DESK.scopes().stream().sorted().toList()));
    List<JsonNode> anonymousRows =
        rows.stream().filter(row -> text(row, "request_id").equals(anonymousId)).toList();
    assertThat(anonymousRows).hasSize(1);
    JsonNode unauthenticated = anonymousRows.getFirst();
    assertDenied(unauthenticated, "anonymous", "UNAUTHENTICATED", anonymousId);
    assertThat(unauthenticated.get("actor_role").isNull()).isTrue();
    assertThat(text(unauthenticated, "scopes")).isEmpty();
  }

  @Test
  void replayAndRejectionWriteNoAuditRow() throws SQLException {
    UUID source = create();
    UUID destination = create();
    postJson("/v1/deposits", amount(source, 100), 201);
    String body = transfer(source, destination, 10);
    String key = UUID.randomUUID().toString();
    String originalId = UUID.randomUUID().toString();
    HttpResponse<String> original =
        api(
            "POST",
            "/v1/transfers",
            token(Role.OPERATOR),
            body,
            "Idempotency-Key",
            key,
            "X-Request-Id",
            originalId);
    assertThat(original.statusCode()).isEqualTo(201);
    String replayId = UUID.randomUUID().toString();
    HttpResponse<String> replay =
        api(
            "POST",
            "/v1/transfers",
            token(Role.OPERATOR),
            body,
            "Idempotency-Key",
            key,
            "X-Request-Id",
            replayId);
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(original.body());
    assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
    String rejectedId = UUID.randomUUID().toString();
    HttpResponse<String> rejected =
        api(
            "POST",
            "/v1/transfers",
            token(Role.OPERATOR),
            transfer(source, destination, 1000),
            "Idempotency-Key",
            UUID.randomUUID().toString(),
            "X-Request-Id",
            rejectedId);
    assertThat(rejected.statusCode()).isEqualTo(422);
    assertThat(text(JSON.readTree(rejected.body()).get("error"), "code"))
        .isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(auditCount(originalId)).isEqualTo(1);
    assertThat(auditCount(replayId)).isZero();
    assertThat(auditCount(rejectedId)).isZero();
  }

  @Test
  void jobActionsAppearInTheTrail() {
    HoldService holds = context.getBean(HoldService.class);
    int expired;
    do {
      expired = holds.expireDue(100);
    } while (expired > 0);
    UUID account = create();
    postJson("/v1/deposits", amount(account, 100), 201);
    Instant expiresAt = clock.instant().plusSeconds(1);
    JsonNode hold =
        postJson(
            "/v1/holds",
            JSON.writeValueAsString(
                Map.of(
                    "account_id",
                    account,
                    "amount",
                    10,
                    "currency",
                    "EUR",
                    "expires_at",
                    expiresAt.toString())),
            201);
    clock.advance(Duration.ofSeconds(2));
    holds.expireDue(100);

    List<JsonNode> rows = items(audit(resource("hold", id(hold))));
    List<JsonNode> expirations =
        rows.stream().filter(row -> text(row, "action").equals("hold.expire")).toList();
    assertThat(expirations).hasSize(1);
    JsonNode expiration = expirations.getFirst();
    assertThat(text(expiration, "actor")).isEqualTo("job:expiry");
    assertThat(expiration.get("actor_role").isNull()).isTrue();
    assertThat(text(expiration, "scopes")).isEmpty();
    assertThat(text(expiration, "outcome")).isEqualTo("SUCCESS");
    assertThat(text(read("/v1/holds/" + id(hold)), "state")).isEqualTo("EXPIRED");
  }

  @Test
  void stateCanBeReconstructed() {
    UUID account = create();
    Instant t1 = start.plusSeconds(1);
    clock.set(t1);
    String requestId = UUID.randomUUID().toString();
    String clientId = "operator-" + UUID.randomUUID();
    HttpResponse<String> response =
        api(
            "POST",
            "/v1/deposits",
            ISSUER.token(clientId, Role.OPERATOR),
            amount(account, 100),
            "Idempotency-Key",
            UUID.randomUUID().toString(),
            "X-Request-Id",
            requestId);
    assertThat(response.statusCode()).isEqualTo(201);
    UUID deposit = id(JSON.readTree(response.body()));
    Instant t2 = t1.plusSeconds(1);
    clock.set(t2);
    postJson("/v1/withdrawals", amount(account, 40), 201);

    JsonNode asOf = read("/v1/accounts/" + account + "/balance?as_of=" + t1);
    assertThat(asOf.get("balance").asLong()).isEqualTo(100);
    assertThat(read("/v1/accounts/" + account + "/balance").get("balance").asLong()).isEqualTo(60);
    List<JsonNode> rows = items(audit("from=" + t1 + "&to=" + t2));
    assertThat(rows).hasSize(1);
    JsonNode row = rows.getFirst();
    assertThat(text(row, "action")).isEqualTo("deposit.post");
    assertThat(text(row, "request_id")).isEqualTo(requestId);
    assertThat(text(row, "actor")).isEqualTo(clientId);
    assertThat(text(row, "transaction_id")).isEqualTo(deposit.toString());
    assertThat(Instant.parse(text(row, "occurred_at"))).isEqualTo(t1);
  }

  @Test
  void validationErrors() {
    UUID resourceId = UUID.randomUUID();
    Map<String, String> invalid =
        Map.ofEntries(
            Map.entry("resource_type=account", "resource_type"),
            Map.entry("resource_id=" + resourceId, "resource_type"),
            Map.entry("resource_type=bogus&resource_id=" + resourceId, "resource_type"),
            Map.entry("from=" + start.plusSeconds(1) + "&to=" + start, "from"),
            Map.entry("limit=0", "limit"),
            Map.entry("limit=501", "limit"),
            Map.entry("cursor=***", "cursor"),
            Map.entry("cursor=" + Cursor.encode("invalid"), "cursor"),
            Map.entry("cursor=" + Cursor.encode("invalid|" + resourceId), "cursor"),
            Map.entry("cursor=" + Cursor.encode(start + "|invalid"), "cursor"),
            Map.entry("cursor=" + Cursor.encode(start + "|1-1-1-1-1"), "cursor"),
            Map.entry("cursor=" + Cursor.encode(start + "|" + resourceId + "|extra"), "cursor"));
    invalid.forEach(
        (query, field) -> {
          HttpResponse<String> response =
              api("GET", "/v1/audit?" + query, token(Role.AUDITOR), null);
          assertThat(response.statusCode()).as(query).isEqualTo(400);
          JsonNode error = JSON.readTree(response.body()).get("error");
          assertThat(text(error, "code")).isEqualTo("VALIDATION_FAILED");
          assertThat(text(error.get("details").get(0), "field")).isEqualTo(field);
        });
  }

  @Test
  void requiresAuditReadScope() {
    String path = "/v1/audit?" + resource("account", create());
    for (Role role : List.of(Role.OPERATOR, Role.ADMIN)) {
      assertThat(api("GET", path, token(role), null).statusCode())
          .as(role.toString())
          .isEqualTo(403);
    }
    for (Role role : List.of(Role.AUDITOR, Role.COMPLIANCE)) {
      assertThat(api("GET", path, token(role), null).statusCode())
          .as(role.toString())
          .isEqualTo(200);
    }
  }

  private UUID create() {
    return id(postJson("/v1/accounts", "{\"currency\":\"EUR\"}", 201));
  }

  private JsonNode postJson(String path, String body, int status) {
    HttpResponse<String> response =
        api("POST", path, token(Role.ADMIN), body, "Idempotency-Key", UUID.randomUUID().toString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    return JSON.readTree(response.body());
  }

  private JsonNode read(String path) {
    HttpResponse<String> response = api("GET", path, token(Role.AUDITOR), null);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private JsonNode audit(String query) {
    return read("/v1/audit?" + query);
  }

  private String window() {
    return "from=" + start + "&to=" + start.plus(Duration.ofHours(1));
  }

  private HttpResponse<String> api(
      String method, String path, String token, String body, String... headers) {
    HttpRequest.Builder builder =
        request(url(context, path), token)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (headers.length > 0) {
      builder.headers(headers);
    }
    return send(builder);
  }

  private long auditCount(String requestId) throws SQLException {
    try (Connection connection = appJdbc();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM ledger.audit_log WHERE request_id = ?")) {
      statement.setString(1, requestId);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
    }
  }

  private static void assertDenied(JsonNode row, String actor, String reason, String requestId) {
    assertThat(text(row, "action")).isEqualTo("request.denied");
    assertThat(text(row, "resource_type")).isEqualTo("request");
    assertThat(text(row, "resource_id")).isEqualTo("POST /v1/deposits");
    assertThat(text(row, "actor")).isEqualTo(actor);
    assertThat(text(row, "outcome")).isEqualTo("DENIED");
    assertThat(text(row, "reason")).isEqualTo(reason);
    assertThat(text(row, "request_id")).isEqualTo(requestId);
    assertThat(row.get("transaction_id").isNull()).isTrue();
  }

  private static List<JsonNode> items(JsonNode page) {
    List<JsonNode> rows = new ArrayList<>();
    page.get("items").forEach(rows::add);
    return rows;
  }

  private static String resource(String type, UUID id) {
    return "resource_type=" + type + "&resource_id=" + id;
  }

  private static UUID id(JsonNode node) {
    return UUID.fromString(text(node, "id"));
  }

  private static String text(JsonNode node, String field) {
    return node.get(field).asString();
  }

  private static String amount(UUID account, long amount) {
    return "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount);
  }

  private static String transfer(UUID source, UUID destination, long amount) {
    return JSON.writeValueAsString(
        Map.of(
            "source_account_id",
            source,
            "destination_account_id",
            destination,
            "amount",
            amount,
            "currency",
            "EUR"));
  }
}
