package tally.ledger.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReversalIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void reversalFlipsSidesAndRestoresBalances() throws Exception {
    UUID source = funded(1000);
    UUID destination = create();
    HttpResponse<String> transfer = transfer(source, destination, 300);
    JsonNode original = JSON.readTree(transfer.body());
    UUID originalId = UUID.fromString(original.get("id").asString());

    HttpResponse<String> response = reverse(originalId, key());

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("kind").asString()).isEqualTo("REVERSAL");
    assertThat(body.get("reverses_transaction_id").asString()).isEqualTo(originalId.toString());
    assertThat(body.get("entries").get(0).get("account_id").asString())
        .isEqualTo(source.toString());
    assertThat(body.get("entries").get(0).get("side").asString()).isEqualTo("CREDIT");
    assertThat(body.get("entries").get(1).get("account_id").asString())
        .isEqualTo(destination.toString());
    assertThat(body.get("entries").get(1).get("side").asString()).isEqualTo("DEBIT");
    assertThat(balance(source)).isEqualTo(1000);
    assertThat(balance(destination)).isZero();

    JsonNode updatedOriginal =
        JSON.readTree(get("/v1/transactions/" + originalId, token(Role.ADMIN)).body());
    assertThat(updatedOriginal.get("reversed_by_transaction_id").asString())
        .isEqualTo(body.get("id").asString());
  }

  @Test
  void reversalOfDepositAndWithdrawal() throws Exception {
    UUID account = create();
    HttpResponse<String> deposit = deposit(account, 500);
    UUID depositId = transactionId(deposit);

    assertThat(balance(account)).isEqualTo(500);
    assertThat(reverse(depositId, key()).statusCode()).isEqualTo(201);
    assertThat(balance(account)).isZero();

    assertThat(deposit(account, 500).statusCode()).isEqualTo(201);
    HttpResponse<String> withdrawal = withdrawal(account, 150);
    UUID withdrawalId = transactionId(withdrawal);
    assertThat(balance(account)).isEqualTo(350);

    assertThat(reverse(withdrawalId, key()).statusCode()).isEqualTo(201);
    assertThat(balance(account)).isEqualTo(500);
  }

  @Test
  void secondReversalIsRejected() throws Exception {
    UUID source = funded(500);
    UUID destination = create();
    UUID originalId = transactionId(transfer(source, destination, 100));
    assertThat(reverse(originalId, key()).statusCode()).isEqualTo(201);

    assertError(reverse(originalId, key()), 422, "ALREADY_REVERSED");
  }

  @Test
  void reversalOfReversalIsRejected() throws Exception {
    UUID source = funded(500);
    UUID destination = create();
    UUID originalId = transactionId(transfer(source, destination, 100));
    UUID reversalId = transactionId(reverse(originalId, key()));

    assertError(reverse(reversalId, key()), 422, "NOT_REVERSIBLE");
  }

  @Test
  void reversalThatWouldOverdrawIsRejected() throws Exception {
    UUID source = funded(1000);
    UUID destination = create();
    UUID originalId = transactionId(transfer(source, destination, 300));
    assertThat(withdrawal(destination, 300).statusCode()).isEqualTo(201);

    assertError(reverse(originalId, key()), 422, "INSUFFICIENT_FUNDS");
    assertThat(balance(source)).isEqualTo(700);
    assertThat(balance(destination)).isZero();
    assertThat(reversalCount(originalId)).isZero();
  }

  @Test
  void reversalIsAllowedOnFrozenAccount() throws Exception {
    UUID source = funded(1000);
    UUID destination = create();
    UUID originalId = transactionId(transfer(source, destination, 300));
    HttpResponse<String> frozen =
        post("/v1/accounts/" + destination + "/freeze", token(Role.ADMIN), "");
    assertThat(frozen.statusCode()).isEqualTo(200);

    assertThat(reverse(originalId, key()).statusCode()).isEqualTo(201);
    assertThat(balance(source)).isEqualTo(1000);
    assertThat(balance(destination)).isZero();
  }

  @Test
  void reversalOnClosedAccountIsRejected() throws Exception {
    UUID account = create();
    UUID depositId = transactionId(deposit(account, 100));
    assertThat(withdrawal(account, 100).statusCode()).isEqualTo(201);
    HttpResponse<String> closed = post("/v1/accounts/" + account + "/close", token(Role.ADMIN), "");
    assertThat(closed.statusCode()).isEqualTo(200);

    assertError(reverse(depositId, key()), 422, "ACCOUNT_CLOSED");
    assertThat(reversalCount(depositId)).isZero();
  }

  @Test
  void reversalReplaysWithSameKey() throws Exception {
    UUID source = funded(500);
    UUID destination = create();
    UUID originalId = transactionId(transfer(source, destination, 100));
    String key = key();

    HttpResponse<String> first = reverse(originalId, key);
    HttpResponse<String> replay = reverse(originalId, key);

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(first.headers().firstValue("Idempotent-Replay")).contains("false");
    assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
    assertThat(reversalCount(originalId)).isEqualTo(1);
  }

  @Test
  void concurrentReversalsPostOnce() throws Exception {
    UUID source = funded(1000);
    UUID destination = create();
    UUID originalId = transactionId(transfer(source, destination, 300));
    String admin = token(Role.ADMIN);
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
                      request(url(reversalPath(originalId)), admin)
                          .timeout(Duration.ofSeconds(30))
                          .header("Idempotency-Key", requestKey)
                          .POST(HttpRequest.BodyPublishers.noBody()));
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      int posted = 0;
      int rejected = 0;
      for (Future<HttpResponse<String>> future : futures) {
        HttpResponse<String> response = future.get(40, TimeUnit.SECONDS);
        if (response.statusCode() == 201) {
          posted++;
        } else if (response.statusCode() == 422) {
          assertError(response, 422, "ALREADY_REVERSED");
          rejected++;
        } else {
          assertError(response, 503, "RETRY_EXHAUSTED");
          rejected++;
        }
      }
      assertThat(posted).isEqualTo(1);
      assertThat(rejected).isEqualTo(4);
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(reversalCount(originalId)).isEqualTo(1);
  }

  @Test
  void reasonBecomesDescription() throws Exception {
    UUID source = funded(500);
    UUID destination = create();
    UUID originalId = transactionId(transfer(source, destination, 100));
    HttpResponse<String> withReason =
        reverseWithBody(originalId, key(), "{\"reason\":\"sent in error\"}");
    JsonNode reasonBody = JSON.readTree(withReason.body());
    assertThat(reasonBody.get("description").asString()).isEqualTo("sent in error");

    UUID secondSource = funded(500);
    UUID secondDestination = create();
    UUID secondOriginalId = transactionId(transfer(secondSource, secondDestination, 100));
    HttpResponse<String> withoutReason = reverse(secondOriginalId, key());
    JsonNode defaultBody = JSON.readTree(withoutReason.body());
    assertThat(defaultBody.get("description").asString())
        .isEqualTo("reversal of " + secondOriginalId);
  }

  @Test
  void unknownTransactionIsNotFound() throws Exception {
    assertError(reverse(UUID.randomUUID(), key()), 404, "NOT_FOUND");
  }

  @Test
  void missingKeyIsRejected() throws Exception {
    UUID source = funded(100);
    UUID destination = create();
    UUID originalId = transactionId(transfer(source, destination, 10));

    assertError(reverseWithoutKey(originalId), 400, "IDEMPOTENCY_KEY_REQUIRED");
  }

  @Test
  void requiresReverseScope() throws Exception {
    UUID source = funded(100);
    UUID destination = create();
    UUID originalId = transactionId(transfer(source, destination, 10));

    assertError(reverse(originalId, key(), token(Role.OPERATOR)), 403, "FORBIDDEN");
  }

  private UUID create() {
    HttpResponse<String> response =
        post("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"EUR\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private UUID funded(long amount) throws Exception {
    UUID account = create();
    assertThat(deposit(account, amount).statusCode()).isEqualTo(201);
    return account;
  }

  private HttpResponse<String> deposit(UUID account, long amount) {
    return money(
        "/v1/deposits",
        "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount));
  }

  private HttpResponse<String> withdrawal(UUID account, long amount) {
    return money(
        "/v1/withdrawals",
        "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount));
  }

  private HttpResponse<String> transfer(UUID source, UUID destination, long amount) {
    String body =
        ("{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\","
                + "\"amount\":%d,\"currency\":\"EUR\"}")
            .formatted(source, destination, amount);
    return money("/v1/transfers", body);
  }

  private HttpResponse<String> money(String path, String body) {
    HttpResponse<String> response =
        post(path, token(Role.OPERATOR), body, "Idempotency-Key", key());
    assertThat(response.statusCode()).isEqualTo(201);
    return response;
  }

  private HttpResponse<String> reverse(UUID id, String key) {
    return reverse(id, key, token(Role.ADMIN));
  }

  private HttpResponse<String> reverse(UUID id, String key, String token) {
    return send(
        request(url(reversalPath(id)), token)
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.noBody()));
  }

  private HttpResponse<String> reverseWithBody(UUID id, String key, String body) {
    return post(reversalPath(id), token(Role.ADMIN), body, "Idempotency-Key", key);
  }

  private HttpResponse<String> reverseWithoutKey(UUID id) {
    return send(
        request(url(reversalPath(id)), token(Role.ADMIN))
            .POST(HttpRequest.BodyPublishers.noBody()));
  }

  private static String reversalPath(UUID id) {
    return "/v1/transactions/" + id + "/reversal";
  }

  private long balance(UUID id) throws Exception {
    HttpResponse<String> response = get("/v1/accounts/" + id + "/balance", token(Role.ADMIN));
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body()).get("balance").asLong();
  }

  private long reversalCount(UUID originalId) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.ledger_transaction WHERE reverses_id = ?")) {
      select.setObject(1, originalId);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private static UUID transactionId(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static void assertError(HttpResponse<String> response, int status, String code)
      throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("error").get("code").asString()).isEqualTo(code);
  }
}
