package tally.proof.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import tally.ledger.check.Violation;
import tally.platform.security.Role;
import tally.proof.harness.Http;
import tally.proof.harness.InProcessLedger;
import tally.proof.harness.LedgerClient;
import tally.proof.harness.Postgres;
import tools.jackson.databind.json.JsonMapper;

public final class ConcurrencyRun {

  private static final int POOL_SIZE = 1000;
  private static final int PAIR_SIZE = 100;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final InProcessLedger ledger;
  private final Workload workload;
  private final boolean tolerateUnexpected;
  private final String runId = UUID.randomUUID().toString();
  private final String token;
  private final LedgerClient client;
  private final UUID[] accounts = new UUID[POOL_SIZE + PAIR_SIZE];
  private final ReentrantLock[] pairLocks = new ReentrantLock[PAIR_SIZE];
  private final LongAdder posted = new LongAdder();
  private final LongAdder insufficient = new LongAdder();
  private final LongAdder exhausted = new LongAdder();
  private final LongAdder unexpected = new LongAdder();
  private final LongAdder depositedSum = new LongAdder();
  private final AtomicInteger pairs = new AtomicInteger();
  private final AtomicInteger doublePostedPairs = new AtomicInteger();
  private final AtomicInteger topups = new AtomicInteger();

  private ConcurrencyRun(InProcessLedger ledger, Workload workload, boolean tolerateUnexpected) {
    this.ledger = ledger;
    this.workload = workload;
    this.tolerateUnexpected = tolerateUnexpected;
    token = ledger.issuer().token(Role.ADMIN);
    client = new LedgerClient(ledger.url(), token);
    for (int index = 0; index < pairLocks.length; index++) {
      pairLocks[index] = new ReentrantLock();
    }
  }

  public static Result run(
      InProcessLedger ledger, Workload workload, int operations, int threads, long seed) {
    return run(ledger, workload, operations, threads, seed, false);
  }

  public static Result run(
      InProcessLedger ledger,
      Workload workload,
      int operations,
      int threads,
      long seed,
      boolean tolerateUnexpected) {
    ConcurrencyRun run = new ConcurrencyRun(ledger, workload, tolerateUnexpected);
    run.setup();
    double seconds = run.execute(operations, threads, seed);
    return run.result(operations, seconds);
  }

  private void setup() {
    List<Runnable> tasks = new ArrayList<>();
    for (int index = 0; index < accounts.length; index++) {
      int account = index;
      tasks.add(
          () -> {
            Http.Response response = client.createAccount(0, true);
            assertThat(response.status())
                .as("account creation response: %s", response.body())
                .isEqualTo(201);
            UUID id = UUID.fromString(response.json().get("id").asString());
            accounts[account] = id;
          });
    }
    try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
      runTasks(executor, tasks);
    }
    for (int index = 0; index < accounts.length; index++) {
      long amount = index < POOL_SIZE ? 1_000_000 : 1000;
      deposit(runId + "-setup-" + index, accounts[index], amount);
    }
  }

  private void deposit(String key, UUID account, long amount) {
    Http.Response response = client.deposit(key, account, amount);
    assertThat(response.status()).as("deposit response: %s", response.body()).isEqualTo(201);
    depositedSum.add(amount);
  }

  private double execute(int operations, int threads, long seed) {
    AtomicInteger nextOperation = new AtomicInteger();
    long start = System.nanoTime();
    try (ExecutorService requests = Executors.newCachedThreadPool();
        ExecutorService workers = Executors.newFixedThreadPool(threads)) {
      List<Runnable> tasks = new ArrayList<>();
      for (int thread = 0; thread < threads; thread++) {
        int index = thread;
        tasks.add(
            () ->
                worker(
                    operations,
                    nextOperation,
                    new SplittableRandom(seed + index),
                    runId + "-" + index + "-",
                    requests));
      }
      runTasks(workers, tasks);
    }
    return (System.nanoTime() - start) / 1_000_000_000.0;
  }

  private void worker(
      int operations,
      AtomicInteger nextOperation,
      SplittableRandom rnd,
      String prefix,
      ExecutorService requests) {
    while (!Thread.currentThread().isInterrupted()) {
      boolean pair = rnd.nextInt(19) == 0;
      int number;
      int count;
      do {
        number = nextOperation.get();
        if (number >= operations) {
          return;
        }
        count = pair && operations - number >= 2 ? 2 : 1;
      } while (!nextOperation.compareAndSet(number, number + count));

      if (count == 2) {
        pair(rnd, prefix + number, prefix + (number + 1), requests);
      } else {
        int source = workload.source(rnd, POOL_SIZE);
        int destination = workload.destination(rnd, POOL_SIZE, source);
        transfer(accounts[source], accounts[destination], rnd.nextLong(1, 2001), prefix + number);
      }
    }
  }

  private void pair(
      SplittableRandom rnd, String firstKey, String secondKey, ExecutorService requests) {
    int index;
    ReentrantLock lock;
    do {
      if (Thread.currentThread().isInterrupted()) {
        throw new IllegalStateException("pair interrupted");
      }
      index = rnd.nextInt(PAIR_SIZE);
      lock = pairLocks[index];
    } while (!lock.tryLock());

    try {
      UUID source = accounts[POOL_SIZE + index];
      Http.Response response = client.balance(source);
      assertThat(response.status()).as("balance response: %s", response.body()).isEqualTo(200);
      long balance = response.json().get("balance").asLong();
      if (balance < 2) {
        deposit(runId + "-topup-" + topups.getAndIncrement(), source, 1000);
        balance = Math.addExact(balance, 1000);
      }
      long amount = balance / 2 + 1;
      UUID firstDestination = accounts[rnd.nextInt(POOL_SIZE)];
      UUID secondDestination = accounts[rnd.nextInt(POOL_SIZE)];
      Future<Boolean> first =
          requests.submit(() -> transfer(source, firstDestination, amount, firstKey));
      Future<Boolean> second = null;
      try {
        second = requests.submit(() -> transfer(source, secondDestination, amount, secondKey));
        boolean firstPosted = awaitRequest(first);
        boolean secondPosted = awaitRequest(second);
        pairs.incrementAndGet();
        if (firstPosted && secondPosted) {
          doublePostedPairs.incrementAndGet();
        }
      } finally {
        first.cancel(true);
        if (second != null) {
          second.cancel(true);
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private boolean transfer(UUID source, UUID destination, long amount, String key) {
    String body =
        JSON.writeValueAsString(
            Map.of(
                "source_account_id", source,
                "destination_account_id", destination,
                "amount", amount,
                "currency", "EUR",
                "description", runId));
    Http.Response response;
    try {
      response = Http.post(ledger.url() + "/v1/transfers", token, body, "Idempotency-Key", key);
    } catch (IllegalStateException e) {
      if (!tolerateUnexpected || !(e.getCause() instanceof IOException)) {
        throw e;
      }
      unexpected.increment();
      return false;
    }

    Outcome outcome;
    try {
      outcome = Outcome.of(response);
    } catch (AssertionError e) {
      if (!tolerateUnexpected) {
        throw e;
      }
      unexpected.increment();
      return false;
    }
    LongAdder counter =
        switch (outcome) {
          case POSTED -> posted;
          case INSUFFICIENT -> insufficient;
          case EXHAUSTED -> exhausted;
        };
    counter.increment();
    return outcome == Outcome.POSTED;
  }

  private static boolean awaitRequest(Future<Boolean> request) {
    try {
      return request.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("pair interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException failure) {
        throw failure;
      }
      if (cause instanceof Error failure) {
        throw failure;
      }
      throw new IllegalStateException("transfer failed", cause);
    }
  }

  private static void runTasks(ExecutorService executor, List<Runnable> tasks) {
    ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(executor);
    List<Future<Void>> futures = new ArrayList<>();
    try {
      for (Runnable task : tasks) {
        futures.add(completion.submit(task, null));
      }
      for (int index = 0; index < tasks.size(); index++) {
        completion.take().get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("worker interrupted", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("worker failed", e.getCause());
    } finally {
      for (Future<Void> future : futures) {
        future.cancel(true);
      }
    }
  }

  private Result result(int operations, double seconds) {
    try {
      List<Violation> violations = ledger.check();
      try (Connection connection = Postgres.shared().app();
          PreparedStatement transfers =
              connection.prepareStatement(
                  "SELECT count(*) FROM ledger.ledger_transaction"
                      + " WHERE kind = 'TRANSFER' AND description = ?");
          PreparedStatement balances =
              connection.prepareStatement(
                  "SELECT coalesce(sum(balance), 0) FROM ledger.account WHERE id = ANY(?)")) {
        transfers.setString(1, runId);
        long transferRows;
        try (ResultSet rows = transfers.executeQuery()) {
          rows.next();
          transferRows = rows.getLong(1);
        }
        Array ids = connection.createArrayOf("uuid", accounts);
        try {
          balances.setArray(1, ids);
          long balanceSum;
          try (ResultSet rows = balances.executeQuery()) {
            rows.next();
            balanceSum = rows.getLong(1);
          }
          return new Result(
              workload,
              operations,
              posted.sum(),
              insufficient.sum(),
              exhausted.sum(),
              pairs.get(),
              doublePostedPairs.get(),
              violations,
              transferRows,
              balanceSum,
              depositedSum.sum(),
              seconds,
              unexpected.sum());
        } finally {
          ids.free();
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to read run results", e);
    }
  }

  public record Result(
      Workload workload,
      int operations,
      long posted,
      long insufficient,
      long exhausted,
      int pairs,
      int doublePostedPairs,
      List<Violation> violations,
      long transferRows,
      long balanceSum,
      long depositedSum,
      double seconds,
      long unexpected) {

    public void assertCorrect() {
      assertThat(doublePostedPairs).as("double posted pairs").isZero();
      assertThat(violations).as("ledger invariants").isEmpty();
      assertThat(posted).as("posted transfers").isEqualTo(transferRows);
      assertThat(balanceSum).as("customer balances").isEqualTo(depositedSum);
      assertThat(posted + insufficient + exhausted).as("transfer outcomes").isEqualTo(operations);
    }
  }
}
