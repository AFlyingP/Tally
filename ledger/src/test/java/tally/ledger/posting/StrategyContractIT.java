package tally.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class StrategyContractIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TRANSFERS = "/v1/transfers";

  @ParameterizedTest
  @ValueSource(strings = {"pessimistic", "optimistic", "serializable"})
  void sequentialOperationsBehaveIdentically(String strategy) throws Exception {
    try (ConfigurableApplicationContext context = startStrategy(strategy)) {
      UUID source = create(context);
      UUID destination = create(context);

      HttpResponse<String> deposited = deposit(context, source, 1000);
      assertThat(deposited.statusCode()).isEqualTo(201);

      String key = key();
      String body = transferBody(source, destination, 300);
      HttpResponse<String> transferred = transfer(context, key, body);
      assertThat(transferred.statusCode()).isEqualTo(201);
      assertThat(transferred.headers().firstValue("Idempotent-Replay")).contains("false");

      HttpResponse<String> rejected = withdraw(context, source, 800);
      assertError(rejected, 422, "INSUFFICIENT_FUNDS");

      HttpResponse<String> withdrawn = withdraw(context, source, 700);
      assertThat(withdrawn.statusCode()).isEqualTo(201);

      HttpResponse<String> replay = transfer(context, key, body);
      assertThat(replay.statusCode()).isEqualTo(201);
      assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");

      HttpResponse<String> changed = transfer(context, key, transferBody(source, destination, 301));
      assertError(changed, 422, "IDEMPOTENCY_KEY_REUSED");

      assertThat(balance(source)).isZero();
      assertThat(balance(destination)).isEqualTo(300);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"pessimistic", "optimistic", "serializable"})
  void concurrentTransfersKeepInvariants(String strategy) throws Exception {
    try (ConfigurableApplicationContext context = startStrategy(strategy)) {
      List<UUID> accounts = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        UUID account = create(context);
        assertThat(deposit(context, account, 5000).statusCode()).isEqualTo(201);
        accounts.add(account);
      }

      List<Callable<List<HttpResponse<String>>>> tasks = new ArrayList<>();
      for (int thread = 0; thread < 16; thread++) {
        int threadIndex = thread;
        tasks.add(
            () -> {
              List<HttpResponse<String>> threadResponses = new ArrayList<>();
              for (int transferIndex = 0; transferIndex < 100; transferIndex++) {
                int sourceIndex = (threadIndex * 3 + transferIndex) % accounts.size();
                int destinationIndex =
                    (sourceIndex + 1 + (threadIndex + transferIndex) % (accounts.size() - 1))
                        % accounts.size();
                UUID source = accounts.get(sourceIndex);
                UUID destination = accounts.get(destinationIndex);
                long amount = 1L + (threadIndex * 19L + transferIndex * 13L) % 50L;
                threadResponses.add(
                    transfer(context, key(), transferBody(source, destination, amount)));
              }
              return threadResponses;
            });
      }

      List<HttpResponse<String>> responses =
          sendConcurrently(tasks, 16).stream().flatMap(List::stream).toList();
      long succeeded = 0;
      for (HttpResponse<String> response : responses) {
        assertThat(response.statusCode()).isIn(201, 422, 503);
        if (response.statusCode() == 201) {
          succeeded++;
        } else if (response.statusCode() == 422) {
          assertError(response, 422, "INSUFFICIENT_FUNDS");
        } else {
          assertError(response, 503, "RETRY_EXHAUSTED");
        }
      }

      for (UUID account : accounts) {
        assertThat(balance(account)).isEqualTo(signedEntryBalance(account));
      }
      assertThat(totalBalance(accounts)).isEqualTo(40000);
      assertThat(succeeded).isEqualTo(transferCount(accounts));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"pessimistic", "optimistic", "serializable"})
  void overdrawPairPostsAtMostOnce(String strategy) throws Exception {
    try (ConfigurableApplicationContext context = startStrategy(strategy)) {
      for (int i = 0; i < 50; i++) {
        UUID source = create(context);
        UUID destination = create(context);
        assertThat(deposit(context, source, 1000).statusCode()).isEqualTo(201);
        assertThat(balance(source)).isEqualTo(1000);
        String body = transferBody(source, destination, 600);
        List<Callable<HttpResponse<String>>> requests =
            List.of(() -> transfer(context, key(), body), () -> transfer(context, key(), body));

        List<HttpResponse<String>> responses = sendConcurrently(requests, 2);
        long succeeded =
            responses.stream().filter(response -> response.statusCode() == 201).count();
        assertThat(succeeded).isLessThanOrEqualTo(1);
        for (HttpResponse<String> response : responses) {
          assertThat(response.statusCode()).isIn(201, 422, 503);
          if (response.statusCode() == 422) {
            assertError(response, 422, "INSUFFICIENT_FUNDS");
          } else if (response.statusCode() == 503) {
            assertError(response, 503, "RETRY_EXHAUSTED");
          }
        }
        assertThat(balance(source)).isBetween(0L, 1000L);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"pessimistic", "optimistic", "serializable"})
  void tenConcurrentRequestsWithOneKeyPostOnce(String strategy) throws Exception {
    try (ConfigurableApplicationContext context = startStrategy(strategy)) {
      UUID source = create(context);
      UUID destination = create(context);
      assertThat(deposit(context, source, 1000).statusCode()).isEqualTo(201);
      String key = key();
      String body = transferBody(source, destination, 100);
      List<Callable<HttpResponse<String>>> requests = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        requests.add(() -> transfer(context, key, body));
      }

      List<HttpResponse<String>> responses = sendConcurrently(requests, 10);
      Set<String> transactionIds = new HashSet<>();
      int succeeded = 0;
      for (HttpResponse<String> response : responses) {
        if (response.statusCode() == 201) {
          succeeded++;
          transactionIds.add(JSON.readTree(response.body()).get("id").asString());
        } else if (response.statusCode() == 409) {
          assertError(response, 409, "REQUEST_IN_FLIGHT");
        } else {
          assertError(response, 503, "RETRY_EXHAUSTED");
        }
      }

      assertThat(succeeded).isGreaterThanOrEqualTo(1);
      assertThat(transactionIds).hasSize(1);
      assertThat(transactionCount(key)).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"pessimistic", "optimistic", "serializable"})
  void attemptsHeaderIsAtLeastOne(String strategy) throws Exception {
    try (ConfigurableApplicationContext context = startStrategy(strategy)) {
      UUID source = create(context);
      UUID destination = create(context);
      HttpResponse<String> deposited = deposit(context, source, 1000);
      String key = key();
      String body = transferBody(source, destination, 100);
      HttpResponse<String> transferred = transfer(context, key, body);
      HttpResponse<String> replayed = transfer(context, key, body);

      for (HttpResponse<String> response : List.of(deposited, transferred, replayed)) {
        assertThat(response.statusCode()).isEqualTo(201);
        int attempts =
            Integer.parseInt(response.headers().firstValue("X-Tally-Attempts").orElseThrow());
        assertThat(attempts).isBetween(1, 10);
      }
    }
  }

  private ConfigurableApplicationContext startStrategy(String strategy) {
    return startApp(Map.of("tally.ledger.strategy", strategy));
  }

  private UUID create(ConfigurableApplicationContext context) {
    HttpResponse<String> response = json(context, "/v1/accounts", "{\"currency\":\"EUR\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private HttpResponse<String> deposit(
      ConfigurableApplicationContext context, UUID account, long amount) {
    String body =
        "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount);
    return json(context, "/v1/deposits", body, "Idempotency-Key", key());
  }

  private HttpResponse<String> withdraw(
      ConfigurableApplicationContext context, UUID account, long amount) {
    String body =
        "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount);
    return json(context, "/v1/withdrawals", body, "Idempotency-Key", key());
  }

  private HttpResponse<String> transfer(
      ConfigurableApplicationContext context, String key, String body) {
    return json(context, TRANSFERS, body, "Idempotency-Key", key);
  }

  private HttpResponse<String> json(
      ConfigurableApplicationContext context, String path, String body, String... headers) {
    var request =
        request(url(context, path), token(Role.OPERATOR))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json");
    if (headers.length > 0) {
      request.headers(headers);
    }
    return send(request.POST(HttpRequest.BodyPublishers.ofString(body)));
  }

  private long balance(UUID account) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT balance FROM ledger.account WHERE id = ?")) {
      select.setObject(1, account);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private long signedEntryBalance(UUID account) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                """
                SELECT coalesce(sum(CASE WHEN e.side = a.normal_side THEN e.amount
                  ELSE -e.amount END), 0)
                FROM ledger.account a LEFT JOIN ledger.entry e ON e.account_id = a.id
                WHERE a.id = ? GROUP BY a.id
                """)) {
      select.setObject(1, account);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private long totalBalance(List<UUID> accounts) throws Exception {
    String parameters = String.join(",", Collections.nCopies(accounts.size(), "?"));
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT coalesce(sum(balance), 0) FROM ledger.account WHERE id IN ("
                    + parameters
                    + ")")) {
      bindAccounts(select, accounts);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private long transferCount(List<UUID> accounts) throws Exception {
    String parameters = String.join(",", Collections.nCopies(accounts.size(), "?"));
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.ledger_transaction t WHERE t.kind = 'TRANSFER'"
                    + " AND EXISTS (SELECT 1 FROM ledger.entry e WHERE e.transaction_id = t.id"
                    + " AND e.account_id IN ("
                    + parameters
                    + "))")) {
      bindAccounts(select, accounts);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
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

  private static void bindAccounts(PreparedStatement statement, List<UUID> accounts)
      throws Exception {
    for (int i = 0; i < accounts.size(); i++) {
      statement.setObject(i + 1, accounts.get(i));
    }
  }

  private <T> List<T> sendConcurrently(List<Callable<T>> tasks, int threads) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    try {
      for (Callable<T> task : tasks) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent requests did not start");
                  }
                  return task.call();
                }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<T> responses = new ArrayList<>();
      for (Future<T> future : futures) {
        responses.add(future.get(90, TimeUnit.SECONDS));
      }
      return responses;
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static String transferBody(UUID source, UUID destination, long amount) {
    return "{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\","
        .concat("\"amount\":%d,\"currency\":\"EUR\"}")
        .formatted(source, destination, amount);
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static void assertError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    JsonNode error = JSON.readTree(response.body()).get("error");
    assertThat(error.get("code").asString()).isEqualTo(code);
  }
}
