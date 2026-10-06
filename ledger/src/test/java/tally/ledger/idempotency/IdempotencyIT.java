package tally.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tally.ledger.api.CanonicalJson;
import tally.ledger.api.MoneyDto;
import tally.ledger.core.RequestHash;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.json.JsonMapper;

class IdempotencyIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TRANSFERS = "/v1/transfers";

  @Test
  void replayReturnsStoredResponseAndPostsNothing() throws Exception {
    UUID source = funded();
    UUID destination = create();
    String body = transfer(source, destination, 100);
    String key = key();

    HttpResponse<String> first = transfer(key, body);
    HttpResponse<String> replay = transfer(key, body);

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(first.headers().firstValue("Idempotent-Replay")).contains("false");
    assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
    assertThat(transactionCount(key)).isEqualTo(1);
    assertThat(balance(source)).isEqualTo(900);
    assertThat(balance(destination)).isEqualTo(100);
  }

  @Test
  void replayOfRejectionReturnsTheSameRejection() throws Exception {
    UUID source = create();
    UUID destination = create();
    String key = key();
    String body = transfer(source, destination, 100);
    HttpResponse<String> first = transfer(key, body);
    assertError(first, 422, "INSUFFICIENT_FUNDS");
    deposit(source, 1000);

    HttpResponse<String> replay = transfer(key, body);

    assertError(replay, 422, "INSUFFICIENT_FUNDS");
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
    assertThat(transactionCount(key)).isZero();
    assertThat(balance(source)).isEqualTo(1000);
    assertThat(balance(destination)).isZero();
  }

  @Test
  void differentBodyWithSameKeyIsRejected() throws Exception {
    UUID source = funded();
    UUID destination = create();
    String key = key();
    String body = transfer(source, destination, 100);
    HttpResponse<String> first = transfer(key, body);
    assertThat(first.statusCode()).isEqualTo(201);

    HttpResponse<String> changed = transfer(key, transfer(source, destination, 101));

    assertError(changed, 422, "IDEMPOTENCY_KEY_REUSED");
    assertThat(changed.headers().firstValue("Idempotent-Replay")).contains("false");
    assertThat(transactionCount(key)).isEqualTo(1);
    assertThat(balance(source)).isEqualTo(900);
    assertThat(balance(destination)).isEqualTo(100);
    HttpResponse<String> replay = transfer(key, body);
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
  }

  @Test
  void sameKeyFromAnotherClientIsIndependent() throws Exception {
    UUID source = funded();
    UUID destination = create();
    String key = key();
    String body = transfer(source, destination, 100);
    HttpResponse<String> first = transfer(key, body);

    HttpResponse<String> admin = post(TRANSFERS, token(Role.ADMIN), body, "Idempotency-Key", key);

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(admin.statusCode()).isEqualTo(201);
    assertThat(admin.headers().firstValue("Idempotent-Replay")).contains("false");
    assertThat(JSON.readTree(admin.body()).get("id").asString())
        .isNotEqualTo(JSON.readTree(first.body()).get("id").asString());
    assertThat(transactionCount(key)).isEqualTo(2);
    assertThat(balance(source)).isEqualTo(800);
    assertThat(balance(destination)).isEqualTo(200);
  }

  @Test
  void tenConcurrentRequestsWithOneKeyPostOnce() throws Exception {
    UUID source = funded();
    UUID destination = create();
    String key = key();
    String body = transfer(source, destination, 100);
    String operator = token(Role.OPERATOR);
    CountDownLatch ready = new CountDownLatch(10);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(10);
    List<Future<HttpResponse<String>>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 10; i++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                  return send(
                      request(url(TRANSFERS), operator)
                          .timeout(Duration.ofSeconds(20))
                          .header("Content-Type", "application/json")
                          .header("Idempotency-Key", key)
                          .POST(HttpRequest.BodyPublishers.ofString(body)));
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      String id = null;
      String storedBody = null;
      int original = 0;
      for (Future<HttpResponse<String>> future : futures) {
        HttpResponse<String> response = future.get(30, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(201);
        String returnedId = JSON.readTree(response.body()).get("id").asString();
        if (id == null) {
          id = returnedId;
          storedBody = response.body();
        }
        assertThat(returnedId).isEqualTo(id);
        assertThat(response.body()).isEqualTo(storedBody);
        if (response.headers().firstValue("Idempotent-Replay").orElseThrow().equals("false")) {
          original++;
        }
      }
      assertThat(original).isEqualTo(1);
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(transactionCount(key)).isEqualTo(1);
    assertThat(balance(source)).isEqualTo(900);
    assertThat(balance(destination)).isEqualTo(100);
  }

  @Test
  void inFlightRequestTimesOutWith409() throws Exception {
    UUID source = funded();
    UUID destination = create();
    String key = key();
    String body = transfer(source, destination, 100);
    try (ConfigurableApplicationContext context =
            startApp(Map.of("tally.ledger.idempotency.wait-ms", "300"));
        Connection lock = appJdbc()) {
      lock.setAutoCommit(false);
      try {
        insertKey(lock, key, source, destination, 0, "");
        long started = System.nanoTime();

        HttpResponse<String> response = transfer(context, key, body);

        assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(3));
        assertError(response, 409, "REQUEST_IN_FLIGHT");
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
      } finally {
        lock.rollback();
      }
      HttpResponse<String> retried = transfer(context, key, body);
      assertThat(retried.statusCode()).isEqualTo(201);
      assertThat(retried.headers().firstValue("Idempotent-Replay")).contains("false");
      assertThat(transactionCount(key)).isEqualTo(1);
    }
  }

  @Test
  void waiterReturnsStoredResponseWhenFirstCommits() throws Exception {
    UUID source = funded();
    UUID destination = create();
    String key = key();
    String body = transfer(source, destination, 100);
    String stored = "{\"id\":\"" + UUID.randomUUID() + "\",\"stored\":true}";
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Connection lock = appJdbc()) {
      lock.setAutoCommit(false);
      try {
        insertKey(lock, key, source, destination, 201, stored);
        int pid;
        try (PreparedStatement select = lock.prepareStatement("SELECT pg_backend_pid()");
            ResultSet row = select.executeQuery()) {
          assertThat(row.next()).isTrue();
          pid = row.getInt(1);
        }
        Future<HttpResponse<String>> waiting = executor.submit(() -> transfer(app(), key, body));
        await().atMost(Duration.ofSeconds(3)).until(() -> hasWaiter(pid));
        Thread.sleep(500);
        lock.commit();

        HttpResponse<String> response = waiting.get(10, TimeUnit.SECONDS);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo(stored);
        assertThat(response.headers().firstValue("Idempotent-Replay")).contains("true");
        assertThat(transactionCount(key)).isZero();
        assertThat(balance(source)).isEqualTo(1000);
        assertThat(balance(destination)).isZero();
      } finally {
        lock.rollback();
      }
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void keyAndEntriesRollBackTogether() throws Exception {
    UUID source = funded();
    UUID destination = create();
    String key = key();
    String body = transfer(source, destination, 100);
    long entriesBefore = entryCount(source, destination);
    try (ConfigurableApplicationContext context =
            startApp(
                Map.of(
                    "tally.ledger.lock-wait-ms", "200", "tally.ledger.retry.max-attempts", "2"));
        Connection lock = appJdbc()) {
      lock.setAutoCommit(false);
      try {
        try (PreparedStatement select =
            lock.prepareStatement("SELECT id FROM ledger.account WHERE id = ? FOR UPDATE")) {
          select.setObject(1, source);
          try (ResultSet row = select.executeQuery()) {
            assertThat(row.next()).isTrue();
          }
        }

        HttpResponse<String> response = transfer(context, key, body);

        assertError(response, 503, "RETRY_EXHAUSTED");
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
        assertThat(keyCount(key)).isZero();
        assertThat(transactionCount(key)).isZero();
        assertThat(entryCount(source, destination)).isEqualTo(entriesBefore);
        assertThat(balance(source)).isEqualTo(1000);
        assertThat(balance(destination)).isZero();
      } finally {
        lock.rollback();
      }
      HttpResponse<String> retried = transfer(context, key, body);
      assertThat(retried.statusCode()).isEqualTo(201);
      assertThat(retried.headers().firstValue("Idempotent-Replay")).contains("false");
      assertThat(transactionCount(key)).isEqualTo(1);
      assertThat(entryCount(source, destination)).isEqualTo(entriesBefore + 2);
    }
  }

  @Test
  void missingKeyIsRejected() {
    String body = transfer(create(), create(), 1);

    assertError(post(TRANSFERS, token(Role.OPERATOR), body), 400, "IDEMPOTENCY_KEY_REQUIRED");
    assertError(transfer("", body), 400, "IDEMPOTENCY_KEY_REQUIRED");
  }

  @Test
  void malformedKeyIsRejected() {
    String body = transfer(create(), create(), 1);
    for (String key : List.of("has space", "x".repeat(129))) {
      HttpResponse<String> response = transfer(key, body);

      assertError(response, 400, "VALIDATION_FAILED");
      assertThat(
              JSON.readTree(response.body())
                  .get("error")
                  .get("details")
                  .get(0)
                  .get("field")
                  .asString())
          .isEqualTo("Idempotency-Key");
    }
  }

  private UUID create() {
    HttpResponse<String> response =
        post("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"EUR\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private UUID funded() {
    UUID customer = create();
    deposit(customer, 1000);
    return customer;
  }

  private void deposit(UUID customer, long amount) {
    HttpResponse<String> response =
        post(
            "/v1/deposits",
            token(Role.OPERATOR),
            "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}"
                .formatted(customer, amount),
            "Idempotency-Key",
            key());
    assertThat(response.statusCode()).isEqualTo(201);
  }

  private HttpResponse<String> transfer(String key, String body) {
    return post(TRANSFERS, token(Role.OPERATOR), body, "Idempotency-Key", key);
  }

  private HttpResponse<String> transfer(
      ConfigurableApplicationContext context, String key, String body) {
    return send(
        request(url(context, TRANSFERS), token(Role.OPERATOR))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private static String transfer(UUID source, UUID destination, long amount) {
    return """
        {"source_account_id":"%s","destination_account_id":"%s","amount":%d,"currency":"EUR"}
        """
        .formatted(source, destination, amount);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private void insertKey(
      Connection db, String key, UUID source, UUID destination, int status, String body)
      throws Exception {
    byte[] hash =
        RequestHash.of(
            "POST",
            TRANSFERS,
            CanonicalJson.of(new MoneyDto.Transfer(source, destination, 100L, "EUR", null)));
    try (PreparedStatement insert =
        db.prepareStatement(
            """
        INSERT INTO ledger.idempotency_key
          (client_id, key, request_hash, status_code, response_body, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """)) {
      insert.setString(1, "test-operator");
      insert.setString(2, key);
      insert.setBytes(3, hash);
      insert.setInt(4, status);
      insert.setString(5, body);
      insert.setObject(6, Instant.now().atOffset(ZoneOffset.UTC));
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }
  }

  private boolean hasWaiter(int pid) throws Exception {
    try (Connection db = superJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))")) {
      select.setInt(1, pid);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getBoolean(1);
      }
    }
  }

  private long transactionCount(String key) throws Exception {
    return count(
        "SELECT count(*) FROM ledger.ledger_transaction t JOIN ledger.idempotency_key k"
            + " ON k.id = t.idempotency_key_id WHERE k.key = ?",
        key);
  }

  private long keyCount(String key) throws Exception {
    return count(
        "SELECT count(*) FROM ledger.idempotency_key WHERE client_id = 'test-operator' AND key = ?",
        key);
  }

  private long balance(UUID id) throws Exception {
    return count("SELECT balance FROM ledger.account WHERE id = ?", id);
  }

  private long entryCount(UUID source, UUID destination) throws Exception {
    return count(
        "SELECT count(*) FROM ledger.entry WHERE account_id IN (?, ?)", source, destination);
  }

  private long count(String sql, Object... parameters) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select = db.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        select.setObject(i + 1, parameters[i]);
      }
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
