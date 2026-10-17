package tally.proof.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import tally.platform.security.Role;
import tally.proof.fault.FaultStack;
import tally.proof.fault.FaultStack.K6Result;
import tally.proof.harness.Http;
import tally.proof.harness.LedgerClient;
import tally.proof.harness.Sizes;
import tools.jackson.databind.json.JsonMapper;

public final class LoadComparison {

  private static final int ACCOUNT_COUNT = 1_000;
  private static final int ACCOUNT_CREATION_THREADS = 16;
  private static final List<String> STRATEGIES =
      List.of("pessimistic", "optimistic", "serializable");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private LoadComparison() {}

  public static List<Map<String, Object>> run(Sizes sizes, long seed) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String strategy : STRATEGIES) {
      rows.addAll(runStrategy(strategy, sizes, seed));
    }
    return List.copyOf(rows);
  }

  private static List<Map<String, Object>> runStrategy(String strategy, Sizes sizes, long seed) {
    FaultStack stack =
        FaultStack.start(
            strategy,
            Set.of(FaultStack.Part.POSTGRES, FaultStack.Part.TOXIPROXY, FaultStack.Part.LEDGER));
    try (stack) {
      LedgerClient client = new LedgerClient(stack.ledgerUrl(), stack.issuer().token(Role.ADMIN));
      List<UUID> accounts = createAccounts(client);
      fundAccounts(client, accounts);
      Path accountsFile = createAccountsFile();
      try {
        writeAccounts(accountsFile, accounts);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String workload : sizes.loadWorkloads()) {
          for (int clients : sizes.loadClients()) {
            rows.add(runRow(stack, accountsFile, strategy, workload, clients, sizes, seed));
          }
        }
        try {
          assertThat(stack.checker()).as("ledger invariants after load for %s", strategy).isEmpty();
        } catch (SQLException e) {
          throw new IllegalStateException("failed to check ledger invariants", e);
        }
        return rows;
      } finally {
        deleteAccounts(accountsFile);
      }
    }
  }

  private static List<UUID> createAccounts(LedgerClient client) {
    UUID[] accounts = new UUID[ACCOUNT_COUNT];
    List<Runnable> tasks = new ArrayList<>(ACCOUNT_COUNT);
    for (int index = 0; index < ACCOUNT_COUNT; index++) {
      int accountIndex = index;
      tasks.add(
          () -> {
            Http.Response response = client.createAccount(0, true);
            assertThat(response.status())
                .as("account creation response: %s", response.body())
                .isEqualTo(201);
            accounts[accountIndex] = UUID.fromString(response.json().get("id").asString());
          });
    }
    try (ExecutorService executor = Executors.newFixedThreadPool(ACCOUNT_CREATION_THREADS)) {
      runTasks(executor, tasks);
    }
    return Arrays.stream(accounts).toList();
  }

  private static void fundAccounts(LedgerClient client, List<UUID> accounts) {
    String setupId = UUID.randomUUID().toString();
    for (int index = 0; index < accounts.size(); index++) {
      Http.Response response =
          client.deposit(setupId + "-" + index, accounts.get(index), 1_000_000);
      assertThat(response.status()).as("deposit response: %s", response.body()).isEqualTo(201);
    }
  }

  private static Path createAccountsFile() {
    try {
      return Files.createTempFile("load-accounts-", ".json");
    } catch (IOException e) {
      throw new UncheckedIOException("failed to create load accounts", e);
    }
  }

  private static void writeAccounts(Path file, List<UUID> accounts) {
    try {
      Files.writeString(
          file,
          JSON.writeValueAsString(accounts.stream().map(UUID::toString).toList()),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write load accounts", e);
    }
  }

  private static void deleteAccounts(Path accountsFile) {
    try {
      Files.deleteIfExists(accountsFile);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to delete load accounts", e);
    }
  }

  private static Map<String, Object> runRow(
      FaultStack stack,
      Path accountsFile,
      String strategy,
      String workload,
      int clients,
      Sizes sizes,
      long seed) {
    List<Double> transfersPerSecond = new ArrayList<>();
    List<Double> p50Ms = new ArrayList<>();
    List<Double> p99Ms = new ArrayList<>();
    List<Double> retries = new ArrayList<>();
    List<Double> exhausted = new ArrayList<>();
    List<Double> insufficient = new ArrayList<>();
    for (int run = 0; run < sizes.loadRuns(); run++) {
      Map<String, String> environment =
          Map.of(
              "TOKEN",
              stack.issuer().token(Role.ADMIN),
              "WORKLOAD",
              workload,
              "VUS",
              Integer.toString(clients),
              "DURATION",
              Integer.toString(sizes.loadSeconds()),
              "RUN_ID",
              UUID.randomUUID().toString(),
              "SEED",
              Long.toString(seed));
      K6Result result = stack.runK6(environment, accountsFile);
      if (result.unexpected() > 0) {
        throw new IllegalStateException("unexpected responses in load run: " + result.unexpected());
      }
      transfersPerSecond.add(result.posted() / result.durationS());
      p50Ms.add(result.p50Ms());
      p99Ms.add(result.p99Ms());
      retries.add((double) result.retries());
      exhausted.add((double) result.exhausted());
      insufficient.add((double) result.insufficient());
    }
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("strategy", strategy);
    row.put("workload", workload);
    row.put("clients", clients);
    row.put("runs", transfersPerSecond.size());
    row.put("transfers_per_s", Results.stats(transfersPerSecond));
    row.put("p50_ms", Results.stats(p50Ms));
    row.put("p99_ms", Results.stats(p99Ms));
    row.put("retries", Results.stats(retries));
    row.put("exhausted", Results.stats(exhausted));
    row.put("insufficient", Results.stats(insufficient));
    return row;
  }

  private static void runTasks(ExecutorService executor, List<Runnable> tasks) {
    ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(executor);
    List<Future<Void>> futures = new ArrayList<>(tasks.size());
    try {
      for (Runnable task : tasks) {
        futures.add(completion.submit(task, null));
      }
      for (int index = 0; index < tasks.size(); index++) {
        completion.take().get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("account creation interrupted", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("account creation failed", e.getCause());
    } finally {
      for (Future<Void> future : futures) {
        future.cancel(true);
      }
    }
  }
}
