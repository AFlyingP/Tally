package tally.proof.model;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** An independent in-memory ledger for sequential API comparisons. */
public final class ReferenceLedger {

  private static final int SETTLEMENT = -1;

  private final ModelAccount settlement = new ModelAccount(new UUID(0, 0), 0, false);
  private final List<ModelAccount> accounts = new ArrayList<>();
  private final List<PostedTx> posted = new ArrayList<>();
  private final Map<String, StoredResult> byKey = new HashMap<>();
  private final List<Request> requests = new ArrayList<>();
  private final List<ModelHold> holds = new ArrayList<>();
  private final List<ModelSchedule> schedules = new ArrayList<>();
  private final List<ModelPayment> payments = new ArrayList<>();
  private Instant now = Instant.EPOCH;

  private record PostedTx(
      String kind, int[] accountIndexes, boolean[] debit, long[] amounts, boolean reversed) {}

  private record StoredResult(String fingerprint, Expected expected) {}

  private record Request(String key, String fingerprint, Runnable resend) {}

  public void setNow(Instant now) {
    this.now = now;
  }

  public List<ModelHold> holds() {
    return List.copyOf(holds);
  }

  public List<ModelSchedule> schedules() {
    return List.copyOf(schedules);
  }

  public List<ModelPayment> payments() {
    return List.copyOf(payments);
  }

  public int addAccount(UUID id, long minBalance, boolean creditNormal) {
    int index = accounts.size();
    accounts.add(new ModelAccount(id, minBalance, creditNormal));
    return index;
  }

  public Expected deposit(String key, int account, long amount) {
    String fingerprint = "DEPOSIT:" + account + ":" + amount;
    return keyed(
        key,
        fingerprint,
        () -> deposit(key, account, amount),
        () -> postCustomer("DEPOSIT", account, amount, false));
  }

  public Expected withdraw(String key, int account, long amount) {
    String fingerprint = "WITHDRAWAL:" + account + ":" + amount;
    return keyed(
        key,
        fingerprint,
        () -> withdraw(key, account, amount),
        () -> postCustomer("WITHDRAWAL", account, amount, true));
  }

  public Expected transfer(String key, int source, int destination, long amount) {
    String fingerprint = "TRANSFER:" + source + ":" + destination + ":" + amount;
    return keyed(
        key,
        fingerprint,
        () -> transfer(key, source, destination, amount),
        () -> {
          if (source == destination) {
            return Expected.error(422, "SAME_ACCOUNT");
          }
          if (!hasAccount(source) || !hasAccount(destination)) {
            return Expected.error(404, "NOT_FOUND");
          }
          return post(
              new PostedTx(
                  "TRANSFER",
                  new int[] {source, destination},
                  new boolean[] {true, false},
                  new long[] {amount, amount},
                  false));
        });
  }

  public Expected reverse(String key, int postedIndex) {
    return keyed(
        key,
        "REVERSAL:" + postedIndex,
        () -> reverse(key, postedIndex),
        () -> reversePosting(postedIndex));
  }

  public Expected placeHold(String key, int account, long amount, Instant expiresAt) {
    return keyed(
        key,
        "HOLD:" + account + ":" + amount + ":" + expiresAt,
        () -> placeHold(key, account, amount, expiresAt),
        () -> {
          Expected error = accountError(account);
          if (error != null) {
            return error;
          }
          if (amount < 1) {
            return Expected.error(422, "INVALID_AMOUNT");
          }
          if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(Duration.ofDays(7)))) {
            return Expected.error(422, "INVALID_EXPIRY");
          }
          try {
            long reserved = Math.addExact(held(account), amount);
            if (Math.addExact(balance(account), -reserved) < accounts.get(account).minBalance()) {
              return Expected.error(422, "INSUFFICIENT_FUNDS");
            }
          } catch (ArithmeticException e) {
            return Expected.error(422, "AMOUNT_OVERFLOW");
          }
          holds.add(new ModelHold(account, amount, expiresAt));
          return Expected.ok(201);
        });
  }

  public Expected captureHold(String key, int hold, long amount, int destination) {
    return keyed(
        key,
        "CAPTURE:" + hold + ":" + amount + ":" + destination,
        () -> captureHold(key, hold, amount, destination),
        () -> {
          if (hold < 0 || hold >= holds.size() || !hasAccount(destination)) {
            return Expected.error(404, "NOT_FOUND");
          }
          ModelHold target = holds.get(hold);
          if (!"ACTIVE".equals(target.state())) {
            return Expected.error(422, "HOLD_NOT_ACTIVE");
          }
          if (!now.isBefore(target.expiresAt())) {
            return Expected.error(422, "HOLD_EXPIRED");
          }
          if (amount < 1) {
            return Expected.error(422, "INVALID_AMOUNT");
          }
          if (amount > target.amount()) {
            return Expected.error(422, "CAPTURE_EXCEEDS_HOLD");
          }
          if (destination == target.account()) {
            return Expected.error(422, "SAME_ACCOUNT");
          }
          Expected expected =
              post(
                  new PostedTx(
                      "HOLD_CAPTURE",
                      new int[] {target.account(), destination},
                      new boolean[] {true, false},
                      new long[] {amount, amount},
                      false),
                  Map.of(target.account(), -target.amount()));
          if (expected.status() == 201) {
            target.state("CAPTURED");
          }
          return expected;
        });
  }

  public Expected releaseHold(String key, int hold) {
    return keyed(
        key,
        "RELEASE:" + hold,
        () -> releaseHold(key, hold),
        () -> {
          if (hold < 0 || hold >= holds.size()) {
            return Expected.error(404, "NOT_FOUND");
          }
          ModelHold target = holds.get(hold);
          if (!"ACTIVE".equals(target.state())) {
            return Expected.error(422, "HOLD_NOT_ACTIVE");
          }
          target.state("RELEASED");
          return Expected.ok(200);
        });
  }

  public void advance(Duration duration) {
    now = now.plus(duration);
    for (ModelHold hold : holds) {
      if ("ACTIVE".equals(hold.state()) && !hold.expiresAt().isAfter(now)) {
        hold.state("EXPIRED");
      }
    }
    runScheduler();
  }

  public Expected createSchedule(
      String key, int source, int destination, long amount, boolean daily, Instant startAt) {
    return keyed(
        key,
        "SCHEDULE:" + source + ":" + destination + ":" + amount + ":" + daily + ":" + startAt,
        () -> createSchedule(key, source, destination, amount, daily, startAt),
        () -> {
          if (source == destination) {
            return Expected.error(422, "SAME_ACCOUNT");
          }
          Expected error = accountError(source);
          if (error != null) {
            return error;
          }
          error = accountError(destination);
          if (error != null) {
            return error;
          }
          ModelSchedule schedule = new ModelSchedule(source, destination, amount, daily, startAt);
          LocalDate date = startAt.atOffset(ZoneOffset.UTC).toLocalDate();
          schedule.addOccurrence(
              new ModelSchedule.ModelOccurrence(date, startAt, "PENDING", 0, startAt, null));
          schedules.add(schedule);
          return Expected.ok(201);
        });
  }

  public void runScheduler() {
    for (int handled = 0; handled < 20; handled++) {
      ModelSchedule selected = null;
      ModelSchedule.ModelOccurrence earliest = null;
      int selectedIndex = -1;
      for (ModelSchedule schedule : schedules) {
        if (!"ACTIVE".equals(schedule.status())) {
          continue;
        }
        List<ModelSchedule.ModelOccurrence> occurrences = schedule.occurrences();
        for (int index = 0; index < occurrences.size(); index++) {
          ModelSchedule.ModelOccurrence occurrence = occurrences.get(index);
          if ("PENDING".equals(occurrence.state())
              && !occurrence.nextAttemptAt().isAfter(now)
              && (earliest == null || occurrence.dueAt().isBefore(earliest.dueAt()))) {
            selected = schedule;
            selectedIndex = index;
            earliest = occurrence;
          }
        }
      }
      if (selected == null) {
        return;
      }
      runOccurrence(selected, selectedIndex, earliest);
    }
  }

  public Expected createPayment(String key, int source, long amount, String behaviour) {
    return keyed(
        key,
        "PAYMENT:" + source + ":" + amount + ":" + behaviour,
        () -> createPayment(key, source, amount, behaviour),
        () -> {
          Expected error = accountError(source);
          if (error != null) {
            return error;
          }
          payments.add(new ModelPayment(source, amount, behaviour));
          return Expected.ok(202);
        });
  }

  public String settlePayment(int payment) {
    ModelPayment target = payments.get(payment);
    if (!"INITIATED".equals(target.state())) {
      return target.state();
    }
    ModelAccount source = accounts.get(target.source());
    if ("CLOSED".equals(source.status()) || "FROZEN".equals(source.status())) {
      target.state("FAILED");
      return target.state();
    }
    long debited;
    try {
      long reserved = Math.addExact(held(target.source()), target.amount());
      if (Math.addExact(source.balance(), -reserved) < source.minBalance()) {
        target.state("FAILED");
        return target.state();
      }
      debited = Math.addExact(source.balance(), -target.amount());
    } catch (ArithmeticException e) {
      target.state("FAILED");
      return target.state();
    }
    switch (target.behaviour()) {
      case "ACCEPT", "CONFIRM_TWICE" -> {
        source.post(debited);
        target.state("SETTLED");
      }
      case "REJECT" -> {
        long original = source.balance();
        source.post(debited);
        source.post(original);
        target.state("REJECTED_COMPENSATED");
      }
      default -> throw new IllegalArgumentException("unknown payment behaviour");
    }
    return target.state();
  }

  public Expected freeze(int account) {
    if (!hasAccount(account)) {
      return Expected.error(404, "NOT_FOUND");
    }
    ModelAccount target = accounts.get(account);
    if ("CLOSED".equals(target.status())) {
      return Expected.error(422, "ACCOUNT_CLOSED");
    }
    if ("FROZEN".equals(target.status())) {
      return Expected.error(422, "ACCOUNT_FROZEN");
    }
    target.status("FROZEN");
    return Expected.ok(200);
  }

  public Expected close(int account) {
    if (!hasAccount(account)) {
      return Expected.error(404, "NOT_FOUND");
    }
    ModelAccount target = accounts.get(account);
    if ("CLOSED".equals(target.status())) {
      return Expected.error(422, "ACCOUNT_CLOSED");
    }
    if (target.balance() != 0 || held(account) != 0) {
      return Expected.error(422, "ACCOUNT_NOT_EMPTY");
    }
    for (ModelPayment payment : payments) {
      if (payment.source() == account && "INITIATED".equals(payment.state())) {
        return Expected.error(422, "ACCOUNT_NOT_EMPTY");
      }
    }
    target.status("CLOSED");
    return Expected.ok(200);
  }

  public Expected replaySame(String key) {
    Request request = request(key);
    request.resend().run();
    return byKey.get(key).expected();
  }

  public Expected replayDifferent(String key) {
    Request request = request(key);
    return replay(byKey.get(key), request.fingerprint() + ":amount+1");
  }

  public long balance(int account) {
    return accounts.get(account).balance();
  }

  public long held(int account) {
    long held = accounts.get(account).held();
    for (ModelHold hold : holds) {
      if (hold.account() == account && "ACTIVE".equals(hold.state())) {
        held = Math.addExact(held, hold.amount());
      }
    }
    return held;
  }

  public long available(int account) {
    return Math.addExact(balance(account), -held(account));
  }

  public int entryCount(int account) {
    return accounts.get(account).entryCount();
  }

  private Expected keyed(
      String key, String fingerprint, Runnable resend, Supplier<Expected> operation) {
    StoredResult stored = byKey.get(key);
    if (stored != null) {
      return replay(stored, fingerprint);
    }
    Expected expected = operation.get();
    if ((expected.status() >= 200 && expected.status() < 300)
        || expected.status() == 422
        || expected.status() == 404) {
      byKey.put(key, new StoredResult(fingerprint, expected));
      requests.add(new Request(key, fingerprint, resend));
    }
    return expected;
  }

  private Expected replay(StoredResult stored, String fingerprint) {
    if (stored.fingerprint().equals(fingerprint)) {
      return stored.expected();
    }
    return Expected.error(422, "IDEMPOTENCY_KEY_REUSED");
  }

  private Request request(String key) {
    for (Request request : requests) {
      if (request.key().equals(key)) {
        return request;
      }
    }
    throw new IllegalArgumentException("unknown request key");
  }

  private Expected postCustomer(String kind, int index, long amount, boolean debit) {
    if (!hasAccount(index)) {
      return Expected.error(404, "NOT_FOUND");
    }
    return post(
        new PostedTx(
            kind,
            new int[] {index, SETTLEMENT},
            new boolean[] {debit, !debit},
            new long[] {amount, amount},
            false));
  }

  private Expected reversePosting(int postedIndex) {
    if (postedIndex < 0 || postedIndex >= posted.size()) {
      return Expected.error(404, "NOT_FOUND");
    }
    PostedTx original = posted.get(postedIndex);
    if ("REVERSAL".equals(original.kind())) {
      return Expected.error(422, "NOT_REVERSIBLE");
    }
    if (original.reversed()) {
      return Expected.error(422, "ALREADY_REVERSED");
    }
    boolean[] debit = new boolean[original.debit().length];
    for (int line = 0; line < debit.length; line++) {
      debit[line] = !original.debit()[line];
    }
    Expected expected =
        post(new PostedTx("REVERSAL", original.accountIndexes(), debit, original.amounts(), false));
    if (expected.status() == 201) {
      posted.set(
          postedIndex,
          new PostedTx(
              original.kind(),
              original.accountIndexes(),
              original.debit(),
              original.amounts(),
              true));
    }
    return expected;
  }

  private Expected post(PostedTx transaction) {
    Expected expected = post(transaction, Map.of());
    if (expected.status() == 201) {
      posted.add(transaction);
    }
    return expected;
  }

  private Expected post(PostedTx transaction, Map<Integer, Long> heldDelta) {
    List<Integer> order = new ArrayList<>();
    for (int index : transaction.accountIndexes()) {
      if (index != SETTLEMENT && !hasAccount(index)) {
        return Expected.error(404, "NOT_FOUND");
      }
      order.add(index);
    }
    order.sort((left, right) -> account(left).id().compareTo(account(right).id()));
    for (int index : order) {
      String status = account(index).status();
      if ("CLOSED".equals(status)) {
        return Expected.error(422, "ACCOUNT_CLOSED");
      }
      if ("FROZEN".equals(status)
          && ("DEPOSIT".equals(transaction.kind())
              || "WITHDRAWAL".equals(transaction.kind())
              || "TRANSFER".equals(transaction.kind()))) {
        return Expected.error(422, "ACCOUNT_FROZEN");
      }
    }
    Map<Integer, Long> balances = new HashMap<>();
    try {
      for (int line = 0; line < transaction.accountIndexes().length; line++) {
        int index = transaction.accountIndexes()[line];
        ModelAccount target = account(index);
        long amount = transaction.amounts()[line];
        long delta = transaction.debit()[line] == target.creditNormal() ? -amount : amount;
        balances.put(index, Math.addExact(balances.getOrDefault(index, target.balance()), delta));
      }
      for (int index : order) {
        ModelAccount target = account(index);
        if (index != SETTLEMENT
            && Math.addExact(
                    balances.get(index),
                    -Math.addExact(held(index), heldDelta.getOrDefault(index, 0L)))
                < target.minBalance()) {
          return Expected.error(422, "INSUFFICIENT_FUNDS");
        }
      }
    } catch (ArithmeticException e) {
      return Expected.error(422, "AMOUNT_OVERFLOW");
    }
    for (int index : order) {
      account(index).post(balances.get(index));
    }
    return Expected.ok(201);
  }

  private Expected accountError(int index) {
    if (!hasAccount(index)) {
      return Expected.error(404, "NOT_FOUND");
    }
    String status = accounts.get(index).status();
    if ("CLOSED".equals(status)) {
      return Expected.error(422, "ACCOUNT_CLOSED");
    }
    if ("FROZEN".equals(status)) {
      return Expected.error(422, "ACCOUNT_FROZEN");
    }
    return null;
  }

  private void runOccurrence(
      ModelSchedule schedule, int index, ModelSchedule.ModelOccurrence occurrence) {
    String state;
    String reason = null;
    int attempts = occurrence.attempts();
    Instant nextAttemptAt = occurrence.nextAttemptAt();
    if (occurrence.dueAt().isBefore(now.minus(Duration.ofDays(7)))) {
      state = "SKIPPED";
      reason = "MISSED_WINDOW";
    } else {
      Expected expected =
          post(
              new PostedTx(
                  "TRANSFER",
                  new int[] {schedule.source(), schedule.destination()},
                  new boolean[] {true, false},
                  new long[] {schedule.amount(), schedule.amount()},
                  false),
              Map.of());
      if (expected.status() == 201) {
        state = "POSTED";
      } else if ("INSUFFICIENT_FUNDS".equals(expected.errorCode())) {
        attempts = Math.addExact(attempts, 1);
        if (attempts <= 3) {
          state = "PENDING";
          nextAttemptAt = now.plus(Duration.ofHours(1));
        } else {
          state = "FAILED";
          reason = expected.errorCode();
        }
      } else {
        state = "FAILED";
        reason = expected.errorCode();
      }
    }
    schedule.occurrence(
        index,
        new ModelSchedule.ModelOccurrence(
            occurrence.date(), occurrence.dueAt(), state, attempts, nextAttemptAt, reason));
    if (!"PENDING".equals(state)) {
      if (schedule.daily()) {
        LocalDate date = occurrence.date().plusDays(1);
        Instant dueAt =
            date.atTime(schedule.startAt().atOffset(ZoneOffset.UTC).toLocalTime())
                .toInstant(ZoneOffset.UTC);
        schedule.addOccurrence(
            new ModelSchedule.ModelOccurrence(date, dueAt, "PENDING", 0, dueAt, null));
      } else {
        schedule.status("COMPLETED");
      }
    }
  }

  private boolean hasAccount(int index) {
    return index >= 0 && index < accounts.size();
  }

  private ModelAccount account(int index) {
    return index == SETTLEMENT ? settlement : accounts.get(index);
  }
}
