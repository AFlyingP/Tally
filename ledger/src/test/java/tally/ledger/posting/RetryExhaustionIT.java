package tally.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.json.JsonMapper;

class RetryExhaustionIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TRANSFERS = "/v1/transfers";

  @Test
  void optimisticExhaustionReturns503AndPostsNothing() throws Exception {
    try (ConfigurableApplicationContext context = startStrategy("optimistic")) {
      UUID source = funded(context);
      UUID destination = create(context);
      ExhaustedRequest exhausted;
      try (VersionChurn churn = new VersionChurn(source)) {
        churn.assertRunning();
        exhausted = exhaustUntil503(context, source, destination);
      }

      assertExhausted(exhausted);
      assertThat(transactionCount(exhausted.key())).isZero();
      assertThat(keyCount(exhausted.key())).isZero();
    }
  }

  @Test
  void serializableExhaustionReturns503AndPostsNothing() throws Exception {
    try (ConfigurableApplicationContext context = startStrategy("serializable")) {
      UUID source = funded(context);
      UUID destination = create(context);
      ExhaustedRequest exhausted;
      try (VersionChurn churn = new VersionChurn(source)) {
        churn.assertRunning();
        exhausted = exhaustUntil503(context, source, destination);
      }

      assertExhausted(exhausted);
      assertThat(transactionCount(exhausted.key())).isZero();
      assertThat(keyCount(exhausted.key())).isZero();
    }
  }

  @Test
  void unknownStrategyStopsStartup() {
    assertThatThrownBy(() -> startApp(Map.of("tally.ledger.strategy", "bogus")))
        .rootCause()
        .hasMessageContaining("invalid configuration: tally.ledger.strategy");
  }

  @Test
  void retryAfterExhaustionSucceedsWithSameKey() throws Exception {
    try (ConfigurableApplicationContext context = startStrategy("optimistic")) {
      UUID source = funded(context);
      UUID destination = create(context);
      ExhaustedRequest exhausted;
      try (VersionChurn churn = new VersionChurn(source)) {
        churn.assertRunning();
        exhausted = exhaustUntil503(context, source, destination);
      }

      assertExhausted(exhausted);
      assertThat(transactionCount(exhausted.key())).isZero();
      assertThat(keyCount(exhausted.key())).isZero();

      HttpResponse<String> retried = transfer(context, exhausted.key(), exhausted.body());

      assertThat(retried.statusCode()).isEqualTo(201);
      assertThat(retried.headers().firstValue("Idempotent-Replay")).contains("false");
      assertThat(transactionCount(exhausted.key())).isEqualTo(1);
    }
  }

  private ConfigurableApplicationContext startStrategy(String strategy) {
    return startApp(
        Map.of(
            "tally.ledger.strategy", strategy,
            "tally.ledger.retry.max-attempts", "3",
            "tally.ledger.retry.cap-ms", "5"));
  }

  private UUID funded(ConfigurableApplicationContext context) {
    UUID account = create(context);
    HttpResponse<String> response =
        json(
            context,
            "/v1/deposits",
            "{\"account_id\":\"%s\",\"amount\":5000,\"currency\":\"EUR\"}".formatted(account),
            key());
    assertThat(response.statusCode()).isEqualTo(201);
    return account;
  }

  private UUID create(ConfigurableApplicationContext context) {
    HttpResponse<String> response = json(context, "/v1/accounts", "{\"currency\":\"EUR\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private ExhaustedRequest exhaustUntil503(
      ConfigurableApplicationContext context, UUID source, UUID destination) {
    String body = transferBody(source, destination);
    for (int i = 0; i < 200; i++) {
      String key = key();
      HttpResponse<String> response = transfer(context, key, body);
      if (response.statusCode() == 503) {
        return new ExhaustedRequest(key, body, response);
      }
      assertThat(response.statusCode()).isEqualTo(201);
    }
    throw new AssertionError("retry exhaustion did not occur");
  }

  private HttpResponse<String> transfer(
      ConfigurableApplicationContext context, String key, String body) {
    return json(context, TRANSFERS, body, key);
  }

  private HttpResponse<String> json(
      ConfigurableApplicationContext context, String path, String body, String key) {
    return send(
        request(url(context, path), token(Role.OPERATOR))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private HttpResponse<String> json(
      ConfigurableApplicationContext context, String path, String body) {
    return send(
        request(url(context, path), token(Role.OPERATOR))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private long transactionCount(String key) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.ledger_transaction t"
                    + " JOIN ledger.idempotency_key k ON k.id = t.idempotency_key_id"
                    + " WHERE k.client_id = 'test-operator' AND k.key = ?")) {
      select.setString(1, key);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private long keyCount(String key) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.idempotency_key"
                    + " WHERE client_id = 'test-operator' AND key = ?")) {
      select.setString(1, key);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private static void assertExhausted(ExhaustedRequest exhausted) {
    assertThat(exhausted.response().statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(exhausted.response().body()).get("error").get("code").asString())
        .isEqualTo("RETRY_EXHAUSTED");
    assertThat(exhausted.response().headers().firstValue("Retry-After")).contains("1");
  }

  private static String transferBody(UUID source, UUID destination) {
    return "{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\","
        .concat("\"amount\":1,\"currency\":\"EUR\"}")
        .formatted(source, destination);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private record ExhaustedRequest(String key, String body, HttpResponse<String> response) {}

  private final class VersionChurn implements AutoCloseable {

    private static final int UPDATER_COUNT = 4;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<SQLException> failure = new AtomicReference<>();
    private final CountDownLatch firstUpdates = new CountDownLatch(UPDATER_COUNT);
    private final Thread[] threads = new Thread[UPDATER_COUNT];

    VersionChurn(UUID source) throws InterruptedException {
      for (int i = 0; i < threads.length; i++) {
        threads[i] = new Thread(() -> updateVersions(source));
        threads[i].start();
      }
      boolean allUpdated;
      try {
        allUpdated = firstUpdates.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        stopAndJoin();
        throw e;
      }
      if (!allUpdated) {
        stopAndJoin();
        throw new IllegalStateException("version updater did not start");
      }
      if (failure.get() != null) {
        stopAndJoin();
        throw new IllegalStateException("version updater failed", failure.get());
      }
    }

    @Override
    public void close() {
      stopAndJoin();
      for (Thread thread : threads) {
        assertThat(thread.isAlive()).isFalse();
      }
      assertThat((Throwable) failure.get()).isNull();
    }

    void assertRunning() {
      assertThat((Throwable) failure.get()).isNull();
      for (Thread thread : threads) {
        assertThat(thread.isAlive()).isTrue();
      }
    }

    private void stopAndJoin() {
      running.set(false);
      boolean interrupted = false;
      for (Thread thread : threads) {
        while (thread.isAlive()) {
          try {
            thread.join();
          } catch (InterruptedException e) {
            interrupted = true;
          }
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    private void updateVersions(UUID source) {
      boolean updated = false;
      try (Connection db = superJdbc();
          PreparedStatement update =
              db.prepareStatement("UPDATE ledger.account SET version = version + 1 WHERE id = ?")) {
        while (running.get()) {
          update.setObject(1, source);
          update.executeUpdate();
          if (!updated) {
            updated = true;
            firstUpdates.countDown();
          }
        }
      } catch (SQLException e) {
        failure.compareAndSet(null, e);
        running.set(false);
      } finally {
        if (!updated) {
          firstUpdates.countDown();
        }
      }
    }
  }
}
