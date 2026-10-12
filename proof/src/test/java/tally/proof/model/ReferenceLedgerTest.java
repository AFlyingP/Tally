package tally.proof.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReferenceLedgerTest {

  private final ReferenceLedger model = new ReferenceLedger();
  private long nextId = 1;

  @Test
  void depositThenTransferUpdatesBalances() {
    int source = account(0);
    int destination = account(0);

    assertThat(model.deposit("deposit", source, 100)).isEqualTo(Expected.ok(201));
    assertThat(model.transfer("transfer", source, destination, 40)).isEqualTo(Expected.ok(201));
    assertAccount(source, 60, 2);
    assertAccount(destination, 40, 1);

    assertThat(model.withdraw("withdraw", destination, 10)).isEqualTo(Expected.ok(201));
    assertAccount(destination, 30, 2);
  }

  @Test
  void transferBelowLimitIsRejected() {
    int source = account(0);
    int destination = account(0);
    model.deposit("deposit", source, 50);

    assertThat(model.transfer("transfer", source, destination, 51))
        .isEqualTo(Expected.error(422, "INSUFFICIENT_FUNDS"));
    assertAccount(source, 50, 1);
    assertAccount(destination, 0, 0);
  }

  @Test
  void overdraftAccountGoesDownToItsLimit() {
    int source = account(-500);
    int destination = account(0);

    assertThat(model.transfer("transfer", source, destination, 500)).isEqualTo(Expected.ok(201));
    assertAccount(source, -500, 1);
    assertAccount(destination, 500, 1);
    assertThat(model.withdraw("withdraw", source, 1))
        .isEqualTo(Expected.error(422, "INSUFFICIENT_FUNDS"));
    assertAccount(source, -500, 1);
    assertAccount(destination, 500, 1);
  }

  @Test
  void sameAccountTransferIsRejected() {
    int account = account(0);
    model.freeze(account);

    assertThat(model.transfer("transfer", account, account, 10))
        .isEqualTo(Expected.error(422, "SAME_ACCOUNT"));
    assertAccount(account, 0, 0);
  }

  @Test
  void replayReturnsStoredResultAndChangesNothing() {
    int source = account(0);
    int destination = account(0);
    Expected deposit = model.deposit("deposit", source, 100);
    Expected transfer = model.transfer("transfer", source, destination, 40);
    model.freeze(source);

    assertThat(model.replaySame("deposit")).isSameAs(deposit);
    assertThat(model.replaySame("transfer")).isSameAs(transfer);
    assertThat(model.deposit("deposit", source, 100)).isSameAs(deposit);
    assertAccount(source, 60, 2);
    assertAccount(destination, 40, 1);
  }

  @Test
  void storedRejectionSurvivesLaterFunding() {
    int source = account(0);
    int destination = account(0);
    Expected rejected = model.transfer("transfer", source, destination, 10);
    assertThat(rejected).isEqualTo(Expected.error(422, "INSUFFICIENT_FUNDS"));
    model.deposit("deposit", source, 100);

    assertThat(model.replaySame("transfer")).isSameAs(rejected);
    assertThat(model.transfer("transfer", source, destination, 10)).isSameAs(rejected);
    assertAccount(source, 100, 1);
    assertAccount(destination, 0, 0);
    assertThat(model.transfer("funded", source, destination, 10)).isEqualTo(Expected.ok(201));
    assertAccount(source, 90, 2);
    assertAccount(destination, 10, 1);
  }

  @Test
  void differentFingerprintIsReused422() {
    int source = account(0);
    int destination = account(0);
    Expected deposit = model.deposit("key", source, 100);

    assertThat(model.replayDifferent("key"))
        .isEqualTo(Expected.error(422, "IDEMPOTENCY_KEY_REUSED"));
    assertThat(model.deposit("key", source, 101))
        .isEqualTo(Expected.error(422, "IDEMPOTENCY_KEY_REUSED"));
    assertThat(model.deposit("key", destination, 100))
        .isEqualTo(Expected.error(422, "IDEMPOTENCY_KEY_REUSED"));
    assertThat(model.withdraw("key", source, 100))
        .isEqualTo(Expected.error(422, "IDEMPOTENCY_KEY_REUSED"));
    assertThat(model.replaySame("key")).isSameAs(deposit);
    assertAccount(source, 100, 1);
    assertAccount(destination, 0, 0);
  }

  @Test
  void reverseRestoresBalancesAndCannotRepeat() {
    int source = account(0);
    int destination = account(0);
    model.deposit("deposit", source, 100);
    model.transfer("transfer", source, destination, 40);

    Expected reversal = model.reverse("reverse", 1);
    assertThat(reversal).isEqualTo(Expected.ok(201));
    assertAccount(source, 100, 3);
    assertAccount(destination, 0, 2);
    assertThat(model.replaySame("reverse")).isSameAs(reversal);
    assertThat(model.reverse("repeat", 1)).isEqualTo(Expected.error(422, "ALREADY_REVERSED"));
    assertThat(model.reverse("reverse-reversal", 2))
        .isEqualTo(Expected.error(422, "NOT_REVERSIBLE"));
    assertAccount(source, 100, 3);
    assertAccount(destination, 0, 2);
  }

  @Test
  void frozenRejectsNewPostingsButAllowsReversal() {
    int source = account(0);
    int destination = account(0);
    model.deposit("deposit", source, 100);
    model.transfer("transfer", source, destination, 40);

    assertThat(model.freeze(source)).isEqualTo(Expected.ok(200));
    assertThat(model.freeze(destination)).isEqualTo(Expected.ok(200));
    assertThat(model.freeze(source)).isEqualTo(Expected.error(422, "ACCOUNT_FROZEN"));
    assertThat(model.deposit("frozen-deposit", source, 1))
        .isEqualTo(Expected.error(422, "ACCOUNT_FROZEN"));
    assertThat(model.withdraw("frozen-withdraw", source, 1))
        .isEqualTo(Expected.error(422, "ACCOUNT_FROZEN"));
    assertThat(model.transfer("frozen-transfer", source, destination, 1))
        .isEqualTo(Expected.error(422, "ACCOUNT_FROZEN"));
    assertAccount(source, 60, 2);
    assertAccount(destination, 40, 1);

    assertThat(model.reverse("reverse", 1)).isEqualTo(Expected.ok(201));
    assertAccount(source, 100, 3);
    assertAccount(destination, 0, 2);
  }

  @Test
  void closeNeedsZeroBalance() {
    int account = account(0);
    model.deposit("deposit", account, 20);

    assertThat(model.close(account)).isEqualTo(Expected.error(422, "ACCOUNT_NOT_EMPTY"));
    assertAccount(account, 20, 1);
    assertThat(model.withdraw("withdraw", account, 20)).isEqualTo(Expected.ok(201));
    assertThat(model.freeze(account)).isEqualTo(Expected.ok(200));
    assertThat(model.close(account)).isEqualTo(Expected.ok(200));
    assertThat(model.close(account)).isEqualTo(Expected.error(422, "ACCOUNT_CLOSED"));
    assertThat(model.freeze(account)).isEqualTo(Expected.error(422, "ACCOUNT_CLOSED"));
    assertThat(model.deposit("closed-deposit", account, 1))
        .isEqualTo(Expected.error(422, "ACCOUNT_CLOSED"));
    assertThat(model.withdraw("closed-withdraw", account, 1))
        .isEqualTo(Expected.error(422, "ACCOUNT_CLOSED"));
    assertThat(model.reverse("closed-reverse", 1)).isEqualTo(Expected.error(422, "ACCOUNT_CLOSED"));
    assertAccount(account, 0, 2);
  }

  @Test
  void statusErrorsFollowAscendingAccountId() {
    int source = account(0);
    int destination = model.addAccount(new UUID(0x0180000000007000L, Long.MIN_VALUE), 0, true);
    model.freeze(source);
    model.close(destination);

    assertThat(model.transfer("transfer", source, destination, 1))
        .isEqualTo(Expected.error(422, "ACCOUNT_CLOSED"));
    assertAccount(source, 0, 0);
    assertAccount(destination, 0, 0);

    int smaller = account(0);
    int larger = account(0);
    model.freeze(smaller);
    model.close(larger);
    assertThat(model.transfer("opposite-order", larger, smaller, 1))
        .isEqualTo(Expected.error(422, "ACCOUNT_FROZEN"));
  }

  @Test
  void statusChecksPrecedeLimitChecks() {
    int source = account(0);
    int destination = account(0);
    model.freeze(destination);

    assertThat(model.transfer("transfer", source, destination, 1))
        .isEqualTo(Expected.error(422, "ACCOUNT_FROZEN"));
    assertAccount(source, 0, 0);
    assertAccount(destination, 0, 0);
  }

  @Test
  void debitNormalAccountsUseOppositeBalanceSigns() {
    int source = model.addAccount(new UUID(0x0190000000007000L, Long.MIN_VALUE), -500, false);
    int destination =
        model.addAccount(new UUID(0x0190000000007000L, Long.MIN_VALUE + 1), -500, false);

    assertThat(model.deposit("deposit", source, 100)).isEqualTo(Expected.ok(201));
    assertAccount(source, -100, 1);
    assertThat(model.withdraw("withdraw", source, 40)).isEqualTo(Expected.ok(201));
    assertAccount(source, -60, 2);
    assertThat(model.transfer("transfer", source, destination, 20)).isEqualTo(Expected.ok(201));
    assertAccount(source, -40, 3);
    assertAccount(destination, -20, 1);
    assertThat(model.reverse("reverse", 2)).isEqualTo(Expected.ok(201));
    assertAccount(source, -60, 4);
    assertAccount(destination, 0, 2);
    assertThat(model.deposit("below-limit", source, 441))
        .isEqualTo(Expected.error(422, "INSUFFICIENT_FUNDS"));
    assertAccount(source, -60, 4);
  }

  @Test
  void destinationLimitRejectsTransferWithoutChangingEitherAccount() {
    int source = account(0);
    int destination = model.addAccount(new UUID(0x0190000000007000L, Long.MIN_VALUE), 0, false);
    model.deposit("deposit", source, 10);

    assertThat(model.transfer("transfer", source, destination, 1))
        .isEqualTo(Expected.error(422, "INSUFFICIENT_FUNDS"));
    assertAccount(source, 10, 1);
    assertAccount(destination, 0, 0);
  }

  @Test
  void rejectedReversalStaysStoredAfterFunding() {
    int account = account(0);
    model.deposit("deposit", account, 100);
    model.withdraw("withdraw", account, 100);

    Expected rejected = model.reverse("reverse", 0);
    assertThat(rejected).isEqualTo(Expected.error(422, "INSUFFICIENT_FUNDS"));
    assertAccount(account, 0, 2);
    model.deposit("fund", account, 100);
    assertThat(model.replaySame("reverse")).isSameAs(rejected);
    assertThat(model.reverse("funded-reverse", 0)).isEqualTo(Expected.ok(201));
    assertAccount(account, 0, 4);
  }

  @Test
  void withdrawalReversalRestoresCustomerBalance() {
    int account = account(-500);
    model.withdraw("withdraw", account, 500);

    assertThat(model.reverse("reverse", 0)).isEqualTo(Expected.ok(201));
    assertAccount(account, 0, 2);
    assertThat(model.close(account)).isEqualTo(Expected.ok(200));
  }

  @Test
  void missingResourcesReturnStoredNotFound() {
    Expected deposit = model.deposit("deposit", 0, 10);
    Expected transfer = model.transfer("transfer", 0, 1, 10);
    Expected reversal = model.reverse("reverse", 0);
    assertThat(deposit).isEqualTo(Expected.error(404, "NOT_FOUND"));
    assertThat(transfer).isEqualTo(Expected.error(404, "NOT_FOUND"));
    assertThat(reversal).isEqualTo(Expected.error(404, "NOT_FOUND"));
    assertThat(model.withdraw("withdraw", 0, 10)).isEqualTo(Expected.error(404, "NOT_FOUND"));
    assertThat(model.deposit("negative-deposit", -1, 10))
        .isEqualTo(Expected.error(404, "NOT_FOUND"));
    assertThat(model.withdraw("negative-withdraw", -1, 10))
        .isEqualTo(Expected.error(404, "NOT_FOUND"));
    assertThat(model.transfer("negative-transfer", -1, 0, 10))
        .isEqualTo(Expected.error(404, "NOT_FOUND"));
    assertThat(model.freeze(0)).isEqualTo(Expected.error(404, "NOT_FOUND"));
    assertThat(model.close(0)).isEqualTo(Expected.error(404, "NOT_FOUND"));
    int account = account(0);
    model.deposit("fund", account, 10);

    assertThat(model.replaySame("deposit")).isSameAs(deposit);
    assertThat(model.replaySame("transfer")).isSameAs(transfer);
    assertThat(model.replaySame("reverse")).isSameAs(reversal);
    assertAccount(account, 10, 1);
  }

  @Test
  void balanceOverflowIsStoredWithoutPartialPosting() {
    int source = account(0);
    int destination = account(0);
    model.deposit("deposit", source, Long.MAX_VALUE);

    Expected rejected = model.deposit("overflow", source, 1);
    assertThat(rejected).isEqualTo(Expected.error(422, "AMOUNT_OVERFLOW"));
    assertAccount(source, Long.MAX_VALUE, 1);
    assertAccount(destination, 0, 0);
    model.transfer("transfer", source, destination, 1);
    assertThat(model.replaySame("overflow")).isSameAs(rejected);
    assertAccount(source, Long.MAX_VALUE - 1, 2);
    assertAccount(destination, 1, 1);
  }

  private int account(long minBalance) {
    return model.addAccount(
        new UUID(0x0190000000007000L, Long.MIN_VALUE | nextId++), minBalance, true);
  }

  private void assertAccount(int account, long balance, int entries) {
    assertThat(model.balance(account)).isEqualTo(balance);
    assertThat(model.held(account)).isZero();
    assertThat(model.available(account)).isEqualTo(balance);
    assertThat(model.entryCount(account)).isEqualTo(entries);
  }
}
