package tally.ledger.holds;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tally.ledger.LedgerApplication;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tally.platform.testing.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HoldIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant START = Instant.parse("2026-01-05T10:00:00Z");

  private MutableClock clock;
  private ConfigurableApplicationContext context;

  @BeforeAll
  void start() {
    clock = new MutableClock(START);
    context =
        LedgerApplication.builder()
            .initializers(
                initializer ->
                    ((GenericApplicationContext) initializer)
                        .registerBean(Clock.class, () -> clock))
            .run(args(Map.of()));
  }

  @BeforeEach
  void resetClock() {
    clock.set(START);
  }

  @AfterAll
  void stop() {
    context.close();
  }

  @Test
  void placeReducesAvailableNotBalance() throws Exception {
    UUID account = funded(1000);
    HttpResponse<String> response = place(account, 400, clock.instant().plusSeconds(3600), key());

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode created = JSON.readTree(response.body());
    UUID holdId = UUID.fromString(created.get("id").asString());
    assertThat(created.get("state").asString()).isEqualTo("ACTIVE");
    assertThat(response.headers().firstValue("Idempotent-Replay")).contains("false");
    JsonNode fetched = JSON.readTree(getAt("/v1/holds/" + holdId, token(Role.OPERATOR)).body());
    assertThat(fetched.get("id").asString()).isEqualTo(holdId.toString());
    assertThat(fetched.get("amount").asLong()).isEqualTo(400);
    assertError(getAt("/v1/holds/" + UUID.randomUUID(), token(Role.OPERATOR)), 404, "NOT_FOUND");

    JsonNode balance = balance(account);
    assertThat(balance.get("balance").asLong()).isEqualTo(1000);
    assertThat(balance.get("held").asLong()).isEqualTo(400);
    assertThat(balance.get("available").asLong()).isEqualTo(600);
  }

  @Test
  void placeBeyondAvailableIsRejected() throws Exception {
    UUID account = funded(1000);
    UUID holdId = holdId(place(account, 400, expiry(), key()));

    assertError(place(account, 601, expiry(), key()), 422, "INSUFFICIENT_FUNDS");
    assertThat(balance(account).get("held").asLong()).isEqualTo(400);
    assertThat(holdState(holdId)).isEqualTo("ACTIVE");
  }

  @Test
  void withdrawalUsesAvailableBalance() throws Exception {
    UUID account = funded(1000);
    assertThat(place(account, 400, expiry(), key()).statusCode()).isEqualTo(201);

    assertError(withdrawal(account, 601), 422, "INSUFFICIENT_FUNDS");
    HttpResponse<String> accepted = withdrawal(account, 600);

    assertThat(accepted.statusCode()).isEqualTo(201);
    JsonNode balance = balance(account);
    assertThat(balance.get("balance").asLong()).isEqualTo(400);
    assertThat(balance.get("held").asLong()).isEqualTo(400);
    assertThat(balance.get("available").asLong()).isZero();
  }

  @Test
  void partialCapturePostsAndReleasesRemainder() throws Exception {
    UUID source = funded(1000);
    UUID destination = create();
    UUID holdId = holdId(place(source, 400, expiry(), key()));

    HttpResponse<String> response = capture(holdId, 250, destination, key());

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode transaction = JSON.readTree(response.body());
    assertThat(transaction.get("kind").asString()).isEqualTo("HOLD_CAPTURE");
    assertThat(transaction.get("amount").asLong()).isEqualTo(250);
    JsonNode sourceBalance = balance(source);
    assertThat(sourceBalance.get("balance").asLong()).isEqualTo(750);
    assertThat(sourceBalance.get("held").asLong()).isZero();
    assertThat(sourceBalance.get("available").asLong()).isEqualTo(750);
    assertThat(balance(destination).get("balance").asLong()).isEqualTo(250);
    JsonNode hold = getHold(holdId);
    assertThat(hold.get("state").asString()).isEqualTo("CAPTURED");
    assertThat(hold.get("captured_amount").asLong()).isEqualTo(250);
    assertThat(hold.get("capture_transaction_id").asString())
        .isEqualTo(transaction.get("id").asString());
  }

  @Test
  void captureTwiceIsRejected() throws Exception {
    UUID source = funded(500);
    UUID destination = create();
    UUID holdId = holdId(place(source, 300, expiry(), key()));
    String requestKey = key();

    HttpResponse<String> first = capture(holdId, 100, destination, requestKey);
    HttpResponse<String> replay = capture(holdId, 100, destination, requestKey);
    HttpResponse<String> anotherKey = capture(holdId, 100, destination, key());

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
    assertError(anotherKey, 422, "HOLD_NOT_ACTIVE");
    assertThat(captureTransactionCount(holdId)).isEqualTo(1);
    assertThat(eventCount(holdId, "CAPTURED")).isEqualTo(1);
  }

  @Test
  void captureAboveHoldIsRejected() throws Exception {
    UUID source = funded(500);
    UUID destination = create();
    UUID holdId = holdId(place(source, 300, expiry(), key()));

    assertError(capture(holdId, 301, destination, key()), 422, "CAPTURE_EXCEEDS_HOLD");
    assertThat(holdState(holdId)).isEqualTo("ACTIVE");
    assertThat(eventCount(holdId, "CAPTURED")).isZero();
  }

  @Test
  void captureToSameAccountAndInternalAccountAreRejected() throws Exception {
    UUID source = funded(500);
    UUID holdId = holdId(place(source, 300, expiry(), key()));
    UUID clearing = internalAccount("CLEARING");

    assertError(capture(holdId, 100, source, key()), 422, "SAME_ACCOUNT");
    assertError(capture(holdId, 100, clearing, key()), 422, "INTERNAL_ACCOUNT");
    assertThat(holdState(holdId)).isEqualTo("ACTIVE");
  }

  @Test
  void releaseRestoresAvailable() throws Exception {
    UUID account = funded(1000);
    UUID holdId = holdId(place(account, 400, expiry(), key()));

    HttpResponse<String> response = release(holdId, key());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("state").asString()).isEqualTo("RELEASED");
    JsonNode balance = balance(account);
    assertThat(balance.get("held").asLong()).isZero();
    assertThat(balance.get("available").asLong()).isEqualTo(1000);
    assertError(release(holdId, key()), 422, "HOLD_NOT_ACTIVE");
  }

  @Test
  void expiryBounds() throws Exception {
    UUID account = funded(1000);

    assertError(place(account, 100, clock.instant(), key()), 400, "VALIDATION_FAILED");
    assertError(
        place(account, 100, clock.instant().plus(Duration.ofDays(7)).plusSeconds(1), key()),
        400,
        "VALIDATION_FAILED");
    assertThat(place(account, 100, clock.instant().plus(Duration.ofDays(7)), key()).statusCode())
        .isEqualTo(201);

    String invalidCurrencyKey = key();
    String invalidCurrency = holdBody(account, 100, expiry(), "ZZZ");
    assertError(
        postAt("/v1/holds", token(Role.OPERATOR), invalidCurrency, invalidCurrencyKey),
        400,
        "VALIDATION_FAILED");
    assertThat(idempotencyKeyCount(invalidCurrencyKey)).isZero();
  }

  @Test
  void captureAfterExpiryTimeIsRejected() throws Exception {
    UUID source = funded(500);
    UUID destination = create();
    UUID holdId = holdId(place(source, 200, clock.instant().plusSeconds(3600), key()));
    clock.advance(Duration.ofHours(1).plusSeconds(1));

    assertError(capture(holdId, 100, destination, key()), 422, "HOLD_EXPIRED");
    assertThat(release(holdId, key()).statusCode()).isEqualTo(200);
  }

  @Test
  void concurrentCapturesPostOnce() throws Exception {
    UUID source = funded(1000);
    UUID destination = create();
    UUID holdId = holdId(place(source, 400, expiry(), key()));
    String operator = token(Role.OPERATOR);
    CountDownLatch ready = new CountDownLatch(5);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(5);
    List<Future<HttpResponse<String>>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 5; i++) {
        String requestKey = key();
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                  return send(
                      request(url(context, "/v1/holds/" + holdId + "/capture"), operator)
                          .timeout(Duration.ofSeconds(30))
                          .header("Content-Type", "application/json")
                          .header("Idempotency-Key", requestKey)
                          .POST(
                              HttpRequest.BodyPublishers.ofString(captureBody(100, destination))));
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      int posted = 0;
      for (Future<HttpResponse<String>> future : futures) {
        HttpResponse<String> response = future.get(40, TimeUnit.SECONDS);
        if (response.statusCode() == 201) {
          posted++;
        } else {
          assertThat(response.statusCode()).isIn(422, 503);
        }
      }
      assertThat(posted).isEqualTo(1);
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(captureTransactionCount(holdId)).isEqualTo(1);
    assertThat(nonPlacedEventCount(holdId)).isEqualTo(1);
  }

  @Test
  void holdEventsAreAppendOnly() throws Exception {
    UUID account = funded(500);
    UUID holdId = holdId(place(account, 100, expiry(), key()));

    SQLException applicationError = null;
    try (Connection db = appJdbc();
        PreparedStatement update =
            db.prepareStatement("UPDATE ledger.hold_event SET actor = actor WHERE hold_id = ?")) {
      update.setObject(1, holdId);
      update.executeUpdate();
    } catch (SQLException e) {
      applicationError = e;
    }
    assertThat(applicationError == null).isFalse();
    assertThat(applicationError.getSQLState()).isEqualTo("42501");

    SQLException ownerError = null;
    try (Connection db = superJdbc();
        PreparedStatement update =
            db.prepareStatement("UPDATE ledger.hold_event SET actor = actor WHERE hold_id = ?")) {
      update.setObject(1, holdId);
      update.executeUpdate();
    } catch (SQLException e) {
      ownerError = e;
    }
    assertThat(ownerError == null).isFalse();
    assertThat(ownerError.getSQLState()).isEqualTo("TL001");
  }

  @Test
  void everyHoldChangeHasOneAuditRow() throws Exception {
    UUID capturedSource = funded(500);
    UUID destination = create();
    UUID capturedHold = holdId(place(capturedSource, 200, expiry(), key()));
    capture(capturedHold, 150, destination, key());

    UUID releasedSource = funded(500);
    UUID releasedHold = holdId(place(releasedSource, 200, expiry(), key()));
    release(releasedHold, key());

    assertAuditEvents(capturedHold, List.of("PLACED", "CAPTURED"));
    assertAuditEvents(releasedHold, List.of("PLACED", "RELEASED"));
  }

  @Test
  void frozenAccountRejectsPlaceButAllowsCaptureAndRelease() throws Exception {
    UUID source = funded(1000);
    UUID destination = create();
    UUID capturedHold = holdId(place(source, 200, expiry(), key()));
    UUID releasedHold = holdId(place(source, 200, expiry(), key()));
    HttpResponse<String> freeze =
        postAt("/v1/accounts/" + source + "/freeze", token(Role.ADMIN), "", null);
    assertThat(freeze.statusCode()).isEqualTo(200);

    assertError(place(source, 100, expiry(), key()), 422, "ACCOUNT_FROZEN");
    assertThat(capture(capturedHold, 100, destination, key()).statusCode()).isEqualTo(201);
    assertThat(release(releasedHold, key()).statusCode()).isEqualTo(200);
    assertThat(balance(source).get("held").asLong()).isZero();
    assertThat(balance(source).get("balance").asLong()).isEqualTo(900);
  }

  private UUID create() throws Exception {
    HttpResponse<String> response =
        postAt("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"EUR\"}", null);
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private UUID funded(long amount) throws Exception {
    UUID account = create();
    HttpResponse<String> deposit =
        postAt("/v1/deposits", token(Role.OPERATOR), amountBody(account, amount), key());
    assertThat(deposit.statusCode()).isEqualTo(201);
    return account;
  }

  private HttpResponse<String> place(UUID account, long amount, Instant expiresAt, String key) {
    return postAt(
        "/v1/holds", token(Role.OPERATOR), holdBody(account, amount, expiresAt, "EUR"), key);
  }

  private HttpResponse<String> capture(UUID holdId, long amount, UUID destination, String key) {
    return postAt(
        "/v1/holds/" + holdId + "/capture",
        token(Role.OPERATOR),
        captureBody(amount, destination),
        key);
  }

  private HttpResponse<String> release(UUID holdId, String key) {
    return postAt("/v1/holds/" + holdId + "/release", token(Role.OPERATOR), "", key);
  }

  private HttpResponse<String> withdrawal(UUID account, long amount) {
    return postAt("/v1/withdrawals", token(Role.OPERATOR), amountBody(account, amount), key());
  }

  private HttpResponse<String> getAt(String path, String token) {
    return send(request(url(context, path), token).GET());
  }

  private HttpResponse<String> postAt(String path, String token, String body, String key) {
    HttpRequest.Builder request =
        request(url(context, path), token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return send(request);
  }

  private JsonNode balance(UUID account) throws Exception {
    HttpResponse<String> response =
        getAt("/v1/accounts/" + account + "/balance", token(Role.OPERATOR));
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private JsonNode getHold(UUID holdId) throws Exception {
    HttpResponse<String> response = getAt("/v1/holds/" + holdId, token(Role.OPERATOR));
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private String holdState(UUID holdId) throws Exception {
    return getHold(holdId).get("state").asString();
  }

  private UUID internalAccount(String kind) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id FROM ledger.account WHERE kind = ? AND currency = 'EUR'")) {
      select.setString(1, kind);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getObject("id", UUID.class);
      }
    }
  }

  private long captureTransactionCount(UUID holdId) throws SQLException {
    String sql =
        "SELECT count(*) FROM ledger.ledger_transaction t JOIN ledger.hold h"
            + " ON h.capture_transaction_id = t.id WHERE h.id = ? AND t.kind = 'HOLD_CAPTURE'";
    return count(sql, holdId);
  }

  private long eventCount(UUID holdId, String type) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.hold_event WHERE hold_id = ? AND type = ?")) {
      select.setObject(1, holdId);
      select.setString(2, type);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private long nonPlacedEventCount(UUID holdId) throws SQLException {
    return count(
        "SELECT count(*) FROM ledger.hold_event WHERE hold_id = ? AND type <> 'PLACED'", holdId);
  }

  private long count(String sql, UUID id) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select = db.prepareStatement(sql)) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private long idempotencyKeyCount(String key) throws SQLException {
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

  private void assertAuditEvents(UUID holdId, List<String> types) throws SQLException {
    String sql =
        "SELECT e.type, e.transaction_id, a.action, a.resource_type, a.resource_id,"
            + " a.transaction_id AS audit_transaction_id FROM ledger.hold_event e"
            + " JOIN ledger.audit_log a ON a.id = e.audit_id WHERE e.hold_id = ? ORDER BY e.id";
    try (Connection db = appJdbc();
        PreparedStatement select = db.prepareStatement(sql)) {
      select.setObject(1, holdId);
      try (ResultSet rows = select.executeQuery()) {
        for (String type : types) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString("type")).isEqualTo(type);
          assertThat(rows.getString("resource_type")).isEqualTo("hold");
          assertThat(rows.getString("resource_id")).isEqualTo(holdId.toString());
          String action =
              switch (type) {
                case "PLACED" -> "hold.place";
                case "CAPTURED" -> "hold.capture";
                case "RELEASED" -> "hold.release";
                default -> throw new IllegalArgumentException(type);
              };
          assertThat(rows.getString("action")).isEqualTo(action);
          if (type.equals("CAPTURED")) {
            assertThat(rows.getObject("audit_transaction_id", UUID.class))
                .isEqualTo(rows.getObject("transaction_id", UUID.class));
          } else {
            assertThat(rows.getObject("audit_transaction_id", UUID.class)).isNull();
          }
        }
        assertThat(rows.next()).isFalse();
      }
    }
  }

  private static String amountBody(UUID account, long amount) {
    return "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount);
  }

  private static String holdBody(UUID account, long amount, Instant expiresAt, String currency) {
    return ("{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"%s\"," + "\"expires_at\":\"%s\"}")
        .formatted(account, amount, currency, expiresAt);
  }

  private static String captureBody(long amount, UUID destination) {
    return "{\"amount\":%d,\"destination_account_id\":\"%s\"}".formatted(amount, destination);
  }

  private static UUID holdId(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private Instant expiry() {
    return clock.instant().plusSeconds(3600);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static void assertError(HttpResponse<String> response, int status, String code)
      throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(JSON.readTree(response.body()).get("error").get("code").asString()).isEqualTo(code);
  }
}
