package tally.proof.fault;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tally.platform.security.Role;
import tally.proof.harness.Http;
import tools.jackson.databind.json.JsonMapper;

/** Sends transfers under load and keeps their requests for recovery after a fault. */
public final class LoadDriver {

  public enum OpOutcome {
    POSTED,
    REJECTED,
    UNRESOLVED
  }

  private static final int ACCOUNT_COUNT = 50;
  private static final int BURST_SIZE = 10;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String runId;
  private final int threads;
  private final Duration duration;
  private final Duration clientTimeout;
  private final long seed;
  private final String baseUrl;
  private final String token;
  private final HttpClient http;
  private final ExecutorService clients;
  private final ExecutorService requests;
  private final List<UUID> accounts = new ArrayList<>();
  private final List<Future<?>> workers = new ArrayList<>();
  private final AtomicInteger sequence = new AtomicInteger();
  private final AtomicInteger bursts = new AtomicInteger();
  private final Map<String, Operation> operations = new ConcurrentHashMap<>();
  private final Map<String, OpOutcome> outcomes = new ConcurrentHashMap<>();
  private final Set<String> resent = ConcurrentHashMap.newKeySet();
  private final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
  private int burstEvery;

  private record Operation(String key, HttpRequest request) {}

  public LoadDriver(
      FaultStack stack,
      String runId,
      int threads,
      Duration duration,
      Duration clientTimeout,
      long seed) {
    this.runId = runId;
    this.threads = threads;
    this.duration = duration;
    this.clientTimeout = clientTimeout;
    this.seed = seed;
    baseUrl = stack.ledgerUrl();
    token = stack.issuer().token(Role.ADMIN);
    http = HttpClient.newBuilder().connectTimeout(clientTimeout).build();
    clients = Executors.newFixedThreadPool(threads);
    requests = Executors.newCachedThreadPool();
  }

  public void setup() {
    try {
      for (int index = 0; index < ACCOUNT_COUNT; index++) {
        Http.Response created =
            send(
                http,
                request(
                    "/v1/accounts",
                    runId + "-setup-" + (2 * index),
                    JSON.writeValueAsString(
                        Map.of("currency", "EUR", "min_balance", 0, "normal_side", "CREDIT"))));
        assertThat(created.status())
            .as("account creation response: %s", created.body())
            .isEqualTo(201);
        UUID account = UUID.fromString(created.json().get("id").asString());
        accounts.add(account);
        Http.Response funded =
            send(
                http,
                request(
                    "/v1/deposits",
                    runId + "-setup-" + (2 * index + 1),
                    JSON.writeValueAsString(
                        Map.of("account_id", account, "amount", 100_000, "currency", "EUR"))));
        assertThat(funded.status()).as("deposit response: %s", funded.body()).isEqualTo(201);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("setup request failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("setup interrupted", e);
    }
  }

  public void start() {
    long end = System.nanoTime() + duration.toNanos();
    long retryEnd = end + Duration.ofSeconds(30).toNanos();
    for (int thread = 0; thread < threads; thread++) {
      int index = thread;
      workers.add(
          clients.submit(
              () -> {
                worker(new SplittableRandom(seed + index), end, retryEnd);
                return null;
              }));
    }
  }

  public void awaitEnd() {
    Throwable failure = null;
    try {
      for (Future<?> worker : workers) {
        try {
          worker.get();
        } catch (ExecutionException e) {
          if (failure == null) {
            failure = e.getCause();
          } else {
            failure.addSuppressed(e.getCause());
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("load interrupted", e);
    } finally {
      clients.close();
      requests.close();
      http.close();
    }
    if (failure != null) {
      throw clientFailure(failure);
    }
  }

  /** Retries pending requests within a shared time limit after the fault has healed. */
  public void resolveUnresolved(Duration max) {
    long end = System.nanoTime() + max.toNanos();
    List<Operation> pending =
        new ArrayList<>(
            operations.values().stream()
                .filter(operation -> outcomes.get(operation.key()) == OpOutcome.UNRESOLVED)
                .toList());
    try (HttpClient recovery = HttpClient.newBuilder().connectTimeout(clientTimeout).build()) {
      while (!pending.isEmpty() && System.nanoTime() - end < 0) {
        TimeUnit.MILLISECONDS.sleep(100);
        for (Operation operation : pending) {
          if (System.nanoTime() - end >= 0) {
            return;
          }
          resent.add(operation.key());
          outcomes.put(operation.key(), attempt(recovery, operation.request()));
        }
        pending.removeIf(operation -> outcomes.get(operation.key()) != OpOutcome.UNRESOLVED);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("recovery interrupted", e);
    }
  }

  public Map<String, OpOutcome> outcomes() {
    return Map.copyOf(outcomes);
  }

  public List<UUID> accounts() {
    return List.copyOf(accounts);
  }

  public String runId() {
    return runId;
  }

  public int resentOperations() {
    return resent.size();
  }

  public int bursts() {
    return bursts.get();
  }

  public List<String> failures() {
    return List.copyOf(failures);
  }

  public void sameKeyBurstEvery(int n) {
    if (n <= 0) {
      throw new IllegalArgumentException("burst interval must be positive");
    }
    burstEvery = n;
  }

  private void worker(SplittableRandom rnd, long end, long retryEnd) throws InterruptedException {
    while (System.nanoTime() - end < 0) {
      int number = sequence.incrementAndGet();
      String key = runId + "-" + number;
      int source = rnd.nextInt(ACCOUNT_COUNT);
      int destination = rnd.nextInt(ACCOUNT_COUNT - 1);
      if (destination >= source) {
        destination++;
      }
      long amount = rnd.nextLong(1, 501);
      String body =
          JSON.writeValueAsString(
              Map.of(
                  "source_account_id",
                  accounts.get(source),
                  "destination_account_id",
                  accounts.get(destination),
                  "amount",
                  amount,
                  "currency",
                  "EUR",
                  "description",
                  key));
      Operation operation = new Operation(key, request("/v1/transfers", key, body));
      operations.put(key, operation);
      outcomes.put(key, OpOutcome.UNRESOLVED);
      OpOutcome outcome =
          burstEvery > 0 && number % burstEvery == 0
              ? burst(operation)
              : attempt(http, operation.request());
      while (outcome == OpOutcome.UNRESOLVED && System.nanoTime() - retryEnd < 0) {
        TimeUnit.MILLISECONDS.sleep(100);
        if (System.nanoTime() - retryEnd >= 0) {
          break;
        }
        resent.add(key);
        outcome = attempt(http, operation.request());
      }
      outcomes.put(key, outcome);
    }
  }

  private OpOutcome burst(Operation operation) throws InterruptedException {
    CountDownLatch ready = new CountDownLatch(BURST_SIZE);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Http.Response>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < BURST_SIZE; index++) {
        futures.add(
            requests.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return attemptResponse(http, operation.request());
                }));
      }
      ready.await();
      bursts.incrementAndGet();
      start.countDown();
      String transactionId = null;
      OpOutcome outcome = OpOutcome.UNRESOLVED;
      for (Future<Http.Response> future : futures) {
        Http.Response response = awaitResponse(future);
        OpOutcome result = outcome(response);
        if (result == OpOutcome.POSTED) {
          String id = response.json().get("id").asString();
          if (transactionId != null && !transactionId.equals(id)) {
            failures.add("burst transaction ids differ for " + operation.key());
          }
          transactionId = id;
          outcome = OpOutcome.POSTED;
        } else if (result == OpOutcome.REJECTED && outcome != OpOutcome.POSTED) {
          outcome = OpOutcome.REJECTED;
        }
      }
      return outcome;
    } finally {
      start.countDown();
      for (Future<Http.Response> future : futures) {
        future.cancel(true);
      }
    }
  }

  private static OpOutcome attempt(HttpClient client, HttpRequest request)
      throws InterruptedException {
    return outcome(attemptResponse(client, request));
  }

  private static Http.Response attemptResponse(HttpClient client, HttpRequest request)
      throws InterruptedException {
    try {
      return send(client, request);
    } catch (IOException e) {
      return null;
    }
  }

  private static OpOutcome outcome(Http.Response response) {
    if (response == null) {
      return OpOutcome.UNRESOLVED;
    }
    int status = response.status();
    if (status == 201) {
      return OpOutcome.POSTED;
    }
    if (status == 422) {
      return OpOutcome.REJECTED;
    }
    assertThat(status == 409 || (status >= 500 && status < 600))
        .as("transfer response: %s %s", status, response.body())
        .isTrue();
    return OpOutcome.UNRESOLVED;
  }

  private static Http.Response awaitResponse(Future<Http.Response> future)
      throws InterruptedException {
    try {
      return future.get();
    } catch (ExecutionException e) {
      throw clientFailure(e.getCause());
    }
  }

  private static RuntimeException clientFailure(Throwable cause) {
    if (cause instanceof RuntimeException failure) {
      return failure;
    }
    if (cause instanceof Error failure) {
      throw failure;
    }
    return new IllegalStateException("client thread failed", cause);
  }

  private HttpRequest request(String path, String key, String body) {
    return HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(clientTimeout)
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .header("Idempotency-Key", key)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
  }

  private static Http.Response send(HttpClient client, HttpRequest request)
      throws IOException, InterruptedException {
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    return new Http.Response(response.statusCode(), response.body(), response.headers().map());
  }
}
