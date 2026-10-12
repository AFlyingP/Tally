package tally.proof.property;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import tally.platform.security.Role;
import tally.proof.gen.Op;
import tally.proof.gen.OpArbitraries;
import tally.proof.harness.FailureFile;
import tally.proof.harness.Http;
import tally.proof.harness.InProcessLedger;
import tally.proof.harness.LedgerClient;
import tally.proof.harness.Seeds;
import tally.proof.model.Expected;
import tally.proof.model.ReferenceLedger;
import tools.jackson.databind.JsonNode;

class LedgerSequenceProperties {

  private static final AtomicLong TRY_COUNTER = new AtomicLong();
  private static InProcessLedger ledger;
  private static String ledgerStrategy;

  private record MoneyRequest(String key, long amount, LongFunction<Http.Response> send) {}

  @BeforeContainer
  static void printSeed() {
    Seeds.print("ledger-sequence");
  }

  @AfterContainer
  static void stopLedger() {
    if (ledger != null) {
      ledger.stop();
      ledger = null;
      ledgerStrategy = null;
    }
  }

  @Property(seed = Seeds.PROPERTY_SEED, shrinking = ShrinkingMode.BOUNDED)
  @Tag("property")
  @AddLifecycleHook(FailureFile.class)
  void pessimisticMatchesModel(@ForAll("sequences") List<Op> ops) throws SQLException {
    run("pessimistic", ops);
  }

  @Property(seed = Seeds.PROPERTY_SEED, shrinking = ShrinkingMode.BOUNDED)
  @Tag("property")
  @AddLifecycleHook(FailureFile.class)
  void optimisticMatchesModel(@ForAll("sequences") List<Op> ops) throws SQLException {
    run("optimistic", ops);
  }

  @Property(seed = Seeds.PROPERTY_SEED, shrinking = ShrinkingMode.BOUNDED)
  @Tag("property")
  @AddLifecycleHook(FailureFile.class)
  void serializableMatchesModel(@ForAll("sequences") List<Op> ops) throws SQLException {
    run("serializable", ops);
  }

  @Provide
  Arbitrary<List<Op>> sequences() {
    return OpArbitraries.sequences();
  }

  private void run(String strategy, List<Op> ops) throws SQLException {
    String selectedStrategy = System.getProperty("proof.strategy");
    if (selectedStrategy != null && !selectedStrategy.equals(strategy)) {
      return;
    }
    if (ledger == null || !strategy.equals(ledgerStrategy)) {
      stopLedger();
      ledger = InProcessLedger.start(strategy, Clock.systemUTC());
      ledgerStrategy = strategy;
    }

    long attempt = TRY_COUNTER.incrementAndGet();
    LedgerClient client = new LedgerClient(ledger.url(), ledger.issuer().token(Role.ADMIN));
    ReferenceLedger model = new ReferenceLedger();
    List<UUID> accounts = new ArrayList<>();
    List<UUID> posted = new ArrayList<>();
    List<MoneyRequest> requests = new ArrayList<>();
    for (long minBalance : new long[] {0, 0, -500, 0}) {
      createAccount(client, model, accounts, minBalance, "initial account " + accounts.size());
    }

    for (int index = 0; index < ops.size(); index++) {
      Op op = ops.get(index);
      String description = "operation " + index + ": " + op;
      String key = strategy + "-" + attempt + "-" + index;
      Http.Response response;
      Expected expected;
      switch (op) {
        case Op.CreateAccount create -> {
          createAccount(client, model, accounts, create.minBalance(), description);
          continue;
        }
        case Op.Deposit deposit -> {
          int account = deposit.account() % accounts.size();
          UUID id = accounts.get(account);
          MoneyRequest request =
              new MoneyRequest(key, deposit.amount(), amount -> client.deposit(key, id, amount));
          requests.add(request);
          response = request.send().apply(request.amount());
          expected = model.deposit(key, account, deposit.amount());
        }
        case Op.Withdraw withdraw -> {
          int account = withdraw.account() % accounts.size();
          UUID id = accounts.get(account);
          MoneyRequest request =
              new MoneyRequest(key, withdraw.amount(), amount -> client.withdraw(key, id, amount));
          requests.add(request);
          response = request.send().apply(request.amount());
          expected = model.withdraw(key, account, withdraw.amount());
        }
        case Op.Transfer transfer -> {
          int source = transfer.source() % accounts.size();
          int destination = transfer.destination() % accounts.size();
          UUID sourceId = accounts.get(source);
          UUID destinationId = accounts.get(destination);
          MoneyRequest request =
              new MoneyRequest(
                  key,
                  transfer.amount(),
                  amount -> client.transfer(key, sourceId, destinationId, amount));
          requests.add(request);
          response = request.send().apply(request.amount());
          expected = model.transfer(key, source, destination, transfer.amount());
        }
        case Op.Reverse reverse -> {
          if (posted.isEmpty()) {
            continue;
          }
          int pick = reverse.pick() % posted.size();
          response = client.reverse(key, posted.get(pick));
          expected = model.reverse(key, pick);
        }
        case Op.RetrySame retry -> {
          if (requests.isEmpty()) {
            continue;
          }
          MoneyRequest request = requests.get(retry.pick() % requests.size());
          response = request.send().apply(request.amount());
          expected = model.replaySame(request.key());
        }
        case Op.RetryDifferent retry -> {
          if (requests.isEmpty()) {
            continue;
          }
          MoneyRequest request = requests.get(retry.pick() % requests.size());
          response = request.send().apply(Math.addExact(request.amount(), 1));
          expected = model.replayDifferent(request.key());
        }
        case Op.Freeze freeze -> {
          int account = freeze.account() % accounts.size();
          response = client.freeze(accounts.get(account));
          expected = model.freeze(account);
        }
        case Op.Close close -> {
          int account = close.account() % accounts.size();
          response = client.close(accounts.get(account));
          expected = model.close(account);
        }
      }
      assertResponse(response, expected, description);
      if (response.status() == 201
          && (op instanceof Op.Deposit
              || op instanceof Op.Withdraw
              || op instanceof Op.Transfer
              || op instanceof Op.Reverse)) {
        posted.add(UUID.fromString(response.json().get("id").asString()));
      }
    }

    for (int account = 0; account < accounts.size(); account++) {
      UUID id = accounts.get(account);
      Http.Response response = client.balance(id);
      assertResponse(response, Expected.ok(200), "account " + account + " balance");
      JsonNode balance = response.json();
      assertThat(balance.get("balance").asLong())
          .as("account %s balance", account)
          .isEqualTo(model.balance(account));
      assertThat(balance.get("held").asLong())
          .as("account %s held", account)
          .isEqualTo(model.held(account));
      assertThat(balance.get("available").asLong())
          .as("account %s available", account)
          .isEqualTo(model.available(account));
      assertThat(client.allEntries(id))
          .as("account %s entries", account)
          .hasSize(model.entryCount(account));
    }
    assertThat(ledger.check()).as("ledger invariants").isEmpty();
  }

  private static void createAccount(
      LedgerClient client,
      ReferenceLedger model,
      List<UUID> accounts,
      long minBalance,
      String description) {
    Http.Response response = client.createAccount(minBalance, true);
    assertResponse(response, Expected.ok(201), description);
    UUID id = UUID.fromString(response.json().get("id").asString());
    model.addAccount(id, minBalance, true);
    accounts.add(id);
  }

  private static void assertResponse(
      Http.Response response, Expected expected, String description) {
    assertThat(response.status()).as("%s status", description).isEqualTo(expected.status());
    assertThat(LedgerClient.errorCode(response))
        .as("%s error code", description)
        .isEqualTo(expected.errorCode());
  }
}
