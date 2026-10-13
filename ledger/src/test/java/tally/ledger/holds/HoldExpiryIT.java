package tally.ledger.holds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tally.ledger.LedgerApplication;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tally.platform.testing.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestMethodOrder(OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HoldExpiryIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant START = Instant.parse("2025-03-01T00:00:00Z");

  private MutableClock clock;
  private ConfigurableApplicationContext context;
  private int testDay;

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
  void advanceClockAndExpireEarlierHolds() {
    clock.set(START.plus(Duration.ofDays(testDay++)));
    int expired;
    do {
      expired = context.getBean(HoldService.class).expireDue(100);
    } while (expired > 0);
  }

  @AfterAll
  void stop() {
    context.close();
  }

  @Test
  @Order(1)
  void expireDueReleasesExpiredHolds() throws Exception {
    UUID account = funded(1000);
    UUID first = place(account, 100, clock.instant().plus(Duration.ofHours(1)));
    UUID second = place(account, 200, clock.instant().plus(Duration.ofHours(2)));
    UUID third = place(account, 300, clock.instant().plus(Duration.ofHours(3)));
    clock.advance(Duration.ofHours(2).plusSeconds(1));

    assertThat(context.getBean(HoldService.class).expireDue(100)).isEqualTo(2);

    assertThat(holdState(first)).isEqualTo("EXPIRED");
    assertThat(holdState(second)).isEqualTo("EXPIRED");
    assertThat(holdState(third)).isEqualTo("ACTIVE");
    assertThat(expiryEventsByJob(first)).isEqualTo(1);
    assertThat(expiryEventsByJob(second)).isEqualTo(1);
    assertThat(balance(account).get("held").asLong()).isEqualTo(300);
  }

  @Test
  @Order(2)
  void expireDueIsSafeToRunConcurrently() throws Exception {
    UUID account = funded(100);
    List<UUID> holds = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      holds.add(place(account, 1, clock.instant().plusSeconds(1)));
    }
    clock.advance(Duration.ofSeconds(2));

    CountDownLatch ready = new CountDownLatch(4);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(4);
    List<Future<Integer>> futures = new ArrayList<>();
    int expired = 0;
    try {
      for (int i = 0; i < 4; i++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                  return context.getBean(HoldService.class).expireDue(100);
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<Integer> future : futures) {
        expired += future.get(40, TimeUnit.SECONDS);
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(expired).isEqualTo(20);
    for (UUID holdId : holds) {
      assertThat(holdState(holdId)).isEqualTo("EXPIRED");
      assertThat(eventCount(holdId, "EXPIRED")).isEqualTo(1);
    }
    assertThat(balance(account).get("held").asLong()).isZero();
  }

  @Test
  @Order(3)
  void jobThreadRunsWhenRoleEnabled() throws Exception {
    try (ConfigurableApplicationContext expiryApp =
        startApp(Map.of("tally.ledger.roles", "api,expiry", "tally.ledger.expiry.poll-ms", "50"))) {
      UUID account = funded(expiryApp, 1000);
      Instant expiresAt = expiryApp.getBean(Clock.class).instant().plusSeconds(1);
      UUID holdId = place(expiryApp, account, 100, expiresAt);

      await()
          .pollInterval(Duration.ofMillis(50))
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(() -> assertThat(holdState(expiryApp, holdId)).isEqualTo("EXPIRED"));
    }
  }

  private UUID funded(long amount) throws Exception {
    return funded(context, amount);
  }

  private UUID funded(ConfigurableApplicationContext app, long amount) throws Exception {
    UUID account = create(app);
    HttpResponse<String> deposit = postAt(app, "/v1/deposits", amountBody(account, amount), key());
    assertThat(deposit.statusCode()).isEqualTo(201);
    return account;
  }

  private UUID create(ConfigurableApplicationContext app) throws Exception {
    HttpResponse<String> response = postAt(app, "/v1/accounts", "{\"currency\":\"EUR\"}", null);
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private UUID place(UUID account, long amount, Instant expiresAt) throws Exception {
    return place(context, account, amount, expiresAt);
  }

  private UUID place(
      ConfigurableApplicationContext app, UUID account, long amount, Instant expiresAt)
      throws Exception {
    String body =
        "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\","
            .concat("\"expires_at\":\"%s\"}")
            .formatted(account, amount, expiresAt);
    HttpResponse<String> response = postAt(app, "/v1/holds", body, key());
    assertThat(response.statusCode()).isEqualTo(201);
    UUID holdId = UUID.fromString(JSON.readTree(response.body()).get("id").asString());
    return holdId;
  }

  private HttpResponse<String> postAt(
      ConfigurableApplicationContext app, String path, String body, String idempotencyKey) {
    var request =
        request(url(app, path), token(Role.OPERATOR))
            .header("Content-Type", "application/json")
            .POST(
                body.isEmpty()
                    ? java.net.http.HttpRequest.BodyPublishers.noBody()
                    : java.net.http.HttpRequest.BodyPublishers.ofString(body));
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return send(request);
  }

  private JsonNode balance(UUID account) throws Exception {
    HttpResponse<String> response = getAt(context, "/v1/accounts/" + account + "/balance");
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private String holdState(UUID holdId) {
    return holdState(context, holdId);
  }

  private String holdState(ConfigurableApplicationContext app, UUID holdId) {
    HttpResponse<String> response = getAt(app, "/v1/holds/" + holdId);
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body()).get("state").asString();
  }

  private HttpResponse<String> getAt(ConfigurableApplicationContext app, String path) {
    return send(request(url(app, path), token(Role.OPERATOR)).GET());
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

  private long expiryEventsByJob(UUID holdId) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.hold_event WHERE hold_id = ?"
                    + " AND type = 'EXPIRED' AND actor = 'job:expiry'")) {
      select.setObject(1, holdId);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private static String amountBody(UUID account, long amount) {
    return "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }
}
