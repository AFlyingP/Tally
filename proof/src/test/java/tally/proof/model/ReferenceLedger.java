package tally.proof.model;

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

  private record PostedTx(
      String kind, int[] accountIndexes, boolean[] debit, long[] amounts, boolean reversed) {}

  private record StoredResult(String fingerprint, Expected expected) {}

  private record Request(String key, String fingerprint, Runnable resend) {}

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
    if (target.balance() != 0 || target.held() != 0) {
      return Expected.error(422, "ACCOUNT_NOT_EMPTY");
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
    return accounts.get(account).held();
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
    if (expected.status() == 201 || expected.status() == 422 || expected.status() == 404) {
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
      if ("FROZEN".equals(status) && !"REVERSAL".equals(transaction.kind())) {
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
            && Math.addExact(balances.get(index), -target.held()) < target.minBalance()) {
          return Expected.error(422, "INSUFFICIENT_FUNDS");
        }
      }
    } catch (ArithmeticException e) {
      return Expected.error(422, "AMOUNT_OVERFLOW");
    }
    for (int index : order) {
      account(index).post(balances.get(index));
    }
    posted.add(transaction);
    return Expected.ok(201);
  }

  private boolean hasAccount(int index) {
    return index >= 0 && index < accounts.size();
  }

  private ModelAccount account(int index) {
    return index == SETTLEMENT ? settlement : accounts.get(index);
  }
}
