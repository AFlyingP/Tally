package tally.proof.property;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.EdgeCasesMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeContainer;
import tally.ledger.holds.HoldService;
import tally.ledger.payments.PaymentOrchestrator;
import tally.ledger.schedule.ScheduleRunner;
import tally.platform.security.Role;
import tally.platform.testing.MutableClock;
import tally.proof.gen.Op;
import tally.proof.gen.OpArbitraries;
import tally.proof.harness.FailureFile;
import tally.proof.harness.Http;
import tally.proof.harness.InProcessExtbank;
import tally.proof.harness.InProcessLedger;
import tally.proof.harness.LedgerClient;
import tally.proof.harness.Seeds;
import tally.proof.model.Expected;
import tally.proof.model.ModelSchedule;
import tally.proof.model.ReferenceLedger;
import tools.jackson.databind.JsonNode;

class LedgerSequenceProperties {

  private static final AtomicLong TRY_COUNTER = new AtomicLong();
  private static final Instant START = Instant.parse("2026-03-10T12:00:00Z");
  private static final List<String> OP_NAMES =
      List.of(
          "Deposit",
          "Withdraw",
          "Transfer",
          "Reverse",
          "RetrySame",
          "RetryDifferent",
          "CreateAccount",
          "Freeze",
          "Close",
          "PlaceHold",
          "CaptureHold",
          "ReleaseHold",
          "AdvanceClock",
          "CreateSchedule",
          "CreatePayment");
  private static final Map<String, OperationStats> STATS = new LinkedHashMap<>();

  static {
    OP_NAMES.forEach(name -> STATS.put(name, new OperationStats()));
  }

  private static InProcessExtbank extbank;
  private static InProcessLedger ledger;
  private static String ledgerStrategy;
  private static MutableClock clock;
  // Schedules of earlier tries share the database; they are cancelled before the next try so
  // that the scheduler's per-run limit only sees the current try's occurrences.
  private static final List<UUID> OPEN_SCHEDULES = new ArrayList<>();

  private record MoneyRequest(String key, long amount, LongFunction<Http.Response> send) {}

  private static final class OperationStats {
    private long applied;
    private long succeeded;
  }

  private record ApiOccurrence(LocalDate date, String state, int attempts, String reason) {}

  @BeforeContainer
  static void printSeed() {
    Seeds.print("ledger-sequence");
  }

  @AfterContainer
  static void stopHarness() {
    stopCurrentHarness();
  }

  @AfterProperty
  void printStats() {
    String line =
        OP_NAMES.stream()
            .map(
                name -> {
                  OperationStats stats = STATS.get(name);
                  return name + "=" + stats.applied + "/" + stats.succeeded;
                })
            .reduce((left, right) -> left + " " + right)
            .orElse("");
    System.out.println("ops " + line);
    STATS
        .values()
        .forEach(
            stats -> {
              stats.applied = 0;
              stats.succeeded = 0;
            });
  }

  @Property(
      seed = Seeds.PROPERTY_SEED,
      shrinking = ShrinkingMode.BOUNDED,
      edgeCases = EdgeCasesMode.NONE)
  @Tag("property")
  @AddLifecycleHook(FailureFile.class)
  void pessimisticMatchesModel(@ForAll("sequences") List<Op> ops) throws SQLException {
    run("pessimistic", ops);
  }

  @Property(
      seed = Seeds.PROPERTY_SEED,
      shrinking = ShrinkingMode.BOUNDED,
      edgeCases = EdgeCasesMode.NONE)
  @Tag("property")
  @AddLifecycleHook(FailureFile.class)
  void optimisticMatchesModel(@ForAll("sequences") List<Op> ops) throws SQLException {
    run("optimistic", ops);
  }

  @Property(
      seed = Seeds.PROPERTY_SEED,
      shrinking = ShrinkingMode.BOUNDED,
      edgeCases = EdgeCasesMode.NONE)
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
      stopCurrentHarness();
      clock = new MutableClock(START);
      extbank = InProcessExtbank.start(clock);
      extbank.rule("PROP-REJECT", "REJECT");
      extbank.rule("PROP-TWICE", "CONFIRM_TWICE");
      ledger = InProcessLedger.start(strategy, clock, extbank);
      ledgerStrategy = strategy;
      OPEN_SCHEDULES.clear();
    }

    long attempt = TRY_COUNTER.incrementAndGet();
    clock.set(START.plus(Duration.ofDays(attempt)));
    LedgerClient client = new LedgerClient(ledger.url(), ledger.issuer().token(Role.ADMIN));
    for (UUID schedule : OPEN_SCHEDULES) {
      Http.Response cancelled = client.cancelSchedule(schedule);
      assertThat(cancelled.status()).as("cancel earlier schedule").isIn(200, 422);
    }
    OPEN_SCHEDULES.clear();
    while (ledger.bean(HoldService.class).expireDue(100) > 0) {
      // Holds of earlier tries are past their expiry after the clock moved a day.
    }
    ReferenceLedger model = new ReferenceLedger();
    model.setNow(clock.instant());
    List<UUID> accounts = new ArrayList<>();
    List<UUID> posted = new ArrayList<>();
    List<MoneyRequest> requests = new ArrayList<>();
    List<UUID> holds = new ArrayList<>();
    List<UUID> schedules = new ArrayList<>();
    List<UUID> payments = new ArrayList<>();
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
          response = client.createAccount(create.minBalance(), true);
          assertResponse(response, Expected.ok(201), description);
          UUID id = UUID.fromString(response.json().get("id").asString());
          model.addAccount(id, create.minBalance(), true);
          accounts.add(id);
          record(op, response);
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
        case Op.PlaceHold place -> {
          int account = place.account() % accounts.size();
          Instant expiresAt = clock.instant().plusSeconds(place.ttlSeconds());
          response = client.placeHold(key, accounts.get(account), place.amount(), expiresAt);
          expected = model.placeHold(key, account, place.amount(), expiresAt);
          assertResponse(response, expected, description);
          if (response.status() == 201) {
            holds.add(UUID.fromString(response.json().get("id").asString()));
          }
          record(op, response);
          assertHoldStates(client, model, holds);
          continue;
        }
        case Op.CaptureHold capture -> {
          if (holds.isEmpty()) {
            continue;
          }
          int pick = capture.pick() % holds.size();
          int destination = capture.destination() % accounts.size();
          response =
              client.captureHold(key, holds.get(pick), capture.amount(), accounts.get(destination));
          expected = model.captureHold(key, pick, capture.amount(), destination);
          assertResponse(response, expected, description);
          record(op, response);
          assertHoldStates(client, model, holds);
          continue;
        }
        case Op.ReleaseHold release -> {
          if (holds.isEmpty()) {
            continue;
          }
          int pick = release.pick() % holds.size();
          response = client.releaseHold(key, holds.get(pick));
          expected = model.releaseHold(key, pick);
          assertResponse(response, expected, description);
          record(op, response);
          assertHoldStates(client, model, holds);
          continue;
        }
        case Op.AdvanceClock advance -> {
          clock.advance(Duration.ofSeconds(advance.seconds()));
          ledger.bean(HoldService.class).expireDue(100);
          ledger.bean(ScheduleRunner.class).runOnce();
          model.advance(Duration.ofSeconds(advance.seconds()));
          recordApplied(op);
          assertScheduleOccurrences(client, model, schedules);
          assertHoldStates(client, model, holds);
          continue;
        }
        case Op.CreateSchedule create -> {
          int source = create.source() % accounts.size();
          int destination = create.destination() % accounts.size();
          Instant startAt =
              clock
                  .instant()
                  .plusSeconds(create.startInSeconds())
                  .plusNanos(schedules.size() * 1_000L);
          response =
              client.createSchedule(
                  key,
                  accounts.get(source),
                  accounts.get(destination),
                  create.amount(),
                  create.daily() ? "DAILY" : "ONCE",
                  startAt);
          expected =
              model.createSchedule(
                  key, source, destination, create.amount(), create.daily(), startAt);
          assertResponse(response, expected, description);
          if (response.status() == 201) {
            UUID schedule = UUID.fromString(response.json().get("id").asString());
            schedules.add(schedule);
            OPEN_SCHEDULES.add(schedule);
          }
          record(op, response);
          assertScheduleOccurrences(client, model, schedules);
          continue;
        }
        case Op.CreatePayment create -> {
          int account = create.account() % accounts.size();
          String[] behaviours = {"ACCEPT", "REJECT", "CONFIRM_TWICE"};
          String behaviour = behaviours[create.behaviour()];
          String creditor =
              switch (behaviour) {
                case "ACCEPT" -> "PROP-ACCEPT";
                case "REJECT" -> "PROP-REJECT";
                case "CONFIRM_TWICE" -> "PROP-TWICE";
                default -> throw new IllegalStateException("unknown payment behaviour");
              };
          int paymentIndex = model.payments().size();
          response = client.createPayment(key, accounts.get(account), create.amount(), creditor);
          expected = model.createPayment(key, account, create.amount(), behaviour);
          assertResponse(response, expected, description);
          record(op, response);
          if (response.status() == 202) {
            UUID paymentId = UUID.fromString(response.json().get("id").asString());
            payments.add(paymentId);
            JsonNode payment = response.json();
            for (int calls = 0; calls < 5 && !terminal(payment.get("state").asString()); calls++) {
              ledger.bean(PaymentOrchestrator.class).runOnce();
              Http.Response current = client.payment(paymentId);
              assertResponse(current, Expected.ok(200), description + " payment state");
              payment = current.json();
            }
            assertThat(terminal(payment.get("state").asString()))
                .as("%s payment reaches a terminal state", description)
                .isTrue();
            assertThat(payment.get("state").asString())
                .as("%s payment state", description)
                .isEqualTo(model.settlePayment(paymentIndex));
          }
          continue;
        }
      }
      assertResponse(response, expected, description);
      record(op, response);
      if (response.status() == 201
          && (op instanceof Op.Deposit
              || op instanceof Op.Withdraw
              || op instanceof Op.Transfer
              || op instanceof Op.Reverse)) {
        posted.add(UUID.fromString(response.json().get("id").asString()));
      }
    }

    assertHoldStates(client, model, holds);
    assertScheduleOccurrences(client, model, schedules);
    for (int payment = 0; payment < payments.size(); payment++) {
      Http.Response response = client.payment(payments.get(payment));
      assertResponse(response, Expected.ok(200), "payment " + payment + " state");
      assertThat(response.json().get("state").asString())
          .as("payment %s state", payment)
          .isEqualTo(model.payments().get(payment).state());
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

  private static void assertHoldStates(
      LedgerClient client, ReferenceLedger model, List<UUID> holds) {
    List<tally.proof.model.ModelHold> expected = model.holds();
    assertThat(holds).hasSize(expected.size());
    for (int index = 0; index < holds.size(); index++) {
      Http.Response response = client.hold(holds.get(index));
      assertResponse(response, Expected.ok(200), "hold " + index + " state");
      assertThat(response.json().get("state").asString())
          .as("hold %s state", index)
          .isEqualTo(expected.get(index).state());
    }
  }

  private static void assertScheduleOccurrences(
      LedgerClient client, ReferenceLedger model, List<UUID> schedules) {
    List<ModelSchedule> expectedSchedules = model.schedules();
    assertThat(schedules).hasSize(expectedSchedules.size());
    for (int index = 0; index < schedules.size(); index++) {
      Http.Response response = client.occurrences(schedules.get(index));
      assertResponse(response, Expected.ok(200), "schedule " + index + " occurrences");
      List<ApiOccurrence> actual = new ArrayList<>();
      response
          .json()
          .get("items")
          .forEach(
              item ->
                  actual.add(
                      new ApiOccurrence(
                          LocalDate.parse(item.get("occurrence_date").asString()),
                          item.get("state").asString(),
                          item.get("attempts").asInt(),
                          item.get("reason").isNull() ? null : item.get("reason").asString())));
      actual.sort(Comparator.comparing(ApiOccurrence::date));
      List<ApiOccurrence> expected =
          expectedSchedules.get(index).occurrences().stream()
              .map(
                  occurrence ->
                      new ApiOccurrence(
                          occurrence.date(),
                          occurrence.state(),
                          occurrence.attempts(),
                          occurrence.reason()))
              .sorted(Comparator.comparing(ApiOccurrence::date))
              .toList();
      assertThat(actual).as("schedule %s occurrences", index).containsExactlyElementsOf(expected);
    }
  }

  private static boolean terminal(String state) {
    return List.of("SETTLED", "REJECTED_COMPENSATED", "MANUAL_REVIEW", "FAILED").contains(state);
  }

  private static void record(Op op, Http.Response response) {
    OperationStats stats = STATS.get(op.getClass().getSimpleName());
    stats.applied++;
    if (response.status() >= 200 && response.status() < 300) {
      stats.succeeded++;
    }
  }

  private static void recordApplied(Op op) {
    STATS.get(op.getClass().getSimpleName()).applied++;
  }

  private static void stopCurrentHarness() {
    if (ledger != null) {
      ledger.stop();
      ledger = null;
      ledgerStrategy = null;
    }
    if (extbank != null) {
      extbank.stop();
      extbank = null;
    }
    clock = null;
  }

  private static void assertResponse(
      Http.Response response, Expected expected, String description) {
    assertThat(response.status()).as("%s status", description).isEqualTo(expected.status());
    assertThat(LedgerClient.errorCode(response))
        .as("%s error code", description)
        .isEqualTo(expected.errorCode());
  }
}
