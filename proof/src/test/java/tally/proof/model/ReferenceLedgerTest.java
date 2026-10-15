package tally.proof.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReferenceLedgerTest {

  private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

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

  @Test
  void holdReducesAvailable() {
    model.setNow(NOW);
    int account = account(0);
    model.deposit("deposit", account, 100);

    Expected placed = model.placeHold("hold", account, 60, NOW.plusSeconds(60));
    assertThat(placed).isEqualTo(Expected.ok(201));
    assertHeldAccount(account, 100, 60, 1);
    assertThat(model.holds()).hasSize(1);
    ModelHold hold = model.holds().getFirst();
    assertThat(hold.account()).isEqualTo(account);
    assertThat(hold.amount()).isEqualTo(60);
    assertThat(hold.expiresAt()).isEqualTo(NOW.plusSeconds(60));
    assertThat(hold.state()).isEqualTo("ACTIVE");
    assertThat(model.replaySame("hold")).isSameAs(placed);
    assertThat(model.holds()).hasSize(1);
    assertThat(model.withdraw("withdraw", account, 41))
        .isEqualTo(Expected.error(422, "INSUFFICIENT_FUNDS"));
    assertHeldAccount(account, 100, 60, 1);
  }

  @Test
  void holdBeyondAvailableRejected() {
    model.setNow(NOW);
    int account = account(-500);
    model.deposit("deposit", account, 100);
    model.placeHold("first", account, 350, NOW.plusSeconds(60));

    Expected rejected = model.placeHold("hold", account, 251, NOW.plusSeconds(60));
    assertThat(rejected).isEqualTo(Expected.error(422, "INSUFFICIENT_FUNDS"));
    assertHeldAccount(account, 100, 350, 1);
    assertThat(model.holds()).hasSize(1);
    model.deposit("fund", account, 1);
    assertThat(model.replaySame("hold")).isSameAs(rejected);
    assertThat(model.placeHold("funded", account, 251, NOW.plusSeconds(60)))
        .isEqualTo(Expected.ok(201));
    assertHeldAccount(account, 101, 601, 2);
  }

  @Test
  void captureMovesCapturedAmountAndReleasesRest() {
    model.setNow(NOW);
    int source = account(0);
    int destination = account(0);
    model.deposit("deposit", source, 100);
    model.placeHold("hold", source, 80, NOW.plusSeconds(60));
    model.placeHold("other", source, 10, NOW.plusSeconds(60));
    model.freeze(source);
    model.freeze(destination);

    Expected captured = model.captureHold("capture", 0, 30, destination);
    assertThat(captured).isEqualTo(Expected.ok(201));
    assertHeldAccount(source, 70, 10, 2);
    assertAccount(destination, 30, 1);
    assertThat(model.holds().getFirst().state()).isEqualTo("CAPTURED");
    assertThat(model.holds().get(1).state()).isEqualTo("ACTIVE");
    assertThat(model.replaySame("capture")).isSameAs(captured);
    assertThat(model.captureHold("again", 0, 1, destination))
        .isEqualTo(Expected.error(422, "HOLD_NOT_ACTIVE"));
    assertHeldAccount(source, 70, 10, 2);
    assertAccount(destination, 30, 1);
  }

  @Test
  void captureAfterExpiryRejected() {
    model.setNow(NOW);
    int source = account(0);
    int destination = account(0);
    model.deposit("deposit", source, 100);
    model.placeHold("hold", source, 80, NOW.plusSeconds(60));
    model.setNow(NOW.plusSeconds(60));

    Expected rejected = model.captureHold("capture", 0, 30, destination);
    assertThat(rejected).isEqualTo(Expected.error(422, "HOLD_EXPIRED"));
    assertHeldAccount(source, 100, 80, 1);
    assertAccount(destination, 0, 0);
    assertThat(model.holds().getFirst().state()).isEqualTo("ACTIVE");
    model.advance(Duration.ZERO);
    assertThat(model.holds().getFirst().state()).isEqualTo("EXPIRED");
    assertThat(model.replaySame("capture")).isSameAs(rejected);
    assertThat(model.captureHold("expired", 0, 30, destination))
        .isEqualTo(Expected.error(422, "HOLD_NOT_ACTIVE"));
    assertAccount(source, 100, 1);
  }

  @Test
  void captureAboveHoldRejected() {
    model.setNow(NOW);
    int source = account(0);
    int destination = account(0);
    model.deposit("deposit", source, 100);
    model.placeHold("hold", source, 80, NOW.plusSeconds(60));

    assertThat(model.captureHold("capture", 0, 81, destination))
        .isEqualTo(Expected.error(422, "CAPTURE_EXCEEDS_HOLD"));
    assertHeldAccount(source, 100, 80, 1);
    assertAccount(destination, 0, 0);
    assertThat(model.holds().getFirst().state()).isEqualTo("ACTIVE");
  }

  @Test
  void releaseTwiceRejected() {
    model.setNow(NOW);
    int account = account(0);
    model.deposit("deposit", account, 100);
    model.placeHold("hold", account, 80, NOW.plusSeconds(60));

    Expected released = model.releaseHold("release", 0);
    assertThat(released).isEqualTo(Expected.ok(200));
    assertThat(model.holds().getFirst().state()).isEqualTo("RELEASED");
    assertAccount(account, 100, 1);
    assertThat(model.replaySame("release")).isSameAs(released);
    assertThat(model.releaseHold("release", 0)).isSameAs(released);
    assertThat(model.releaseHold("again", 0)).isEqualTo(Expected.error(422, "HOLD_NOT_ACTIVE"));
    model.advance(Duration.ofMinutes(1));
    assertThat(model.holds().getFirst().state()).isEqualTo("RELEASED");
    assertAccount(account, 100, 1);
  }

  @Test
  void advanceExpiresDueHolds() {
    model.setNow(NOW);
    int source = account(0);
    int other = account(-500);
    model.deposit("deposit", source, 100);
    model.placeHold("first", source, 30, NOW.plusSeconds(60));
    model.placeHold("later", source, 40, NOW.plusSeconds(120));
    model.placeHold("other", other, 50, NOW.plusSeconds(60));

    model.advance(Duration.ofSeconds(60));
    assertThat(model.holds())
        .extracting(ModelHold::state)
        .containsExactly("EXPIRED", "ACTIVE", "EXPIRED");
    assertHeldAccount(source, 100, 40, 1);
    assertAccount(other, 0, 0);
    model.advance(Duration.ofSeconds(60));
    assertThat(model.holds())
        .extracting(ModelHold::state)
        .containsExactly("EXPIRED", "EXPIRED", "EXPIRED");
    assertAccount(source, 100, 1);
    assertAccount(other, 0, 0);
  }

  @Test
  void scheduledTransferPostsWhenDue() {
    model.setNow(NOW);
    int source = account(0);
    int destination = account(0);
    Instant due = NOW.plus(Duration.ofHours(1));
    model.deposit("deposit", source, 100);
    model.placeHold("hold", source, 100, due);

    Expected created = model.createSchedule("schedule", source, destination, 40, false, due);
    assertThat(created).isEqualTo(Expected.ok(201));
    assertThat(model.replaySame("schedule")).isSameAs(created);
    assertThat(model.schedules()).hasSize(1);
    ModelSchedule schedule = model.schedules().getFirst();
    assertThat(schedule.status()).isEqualTo("ACTIVE");
    assertThat(schedule.occurrences())
        .containsExactly(
            new ModelSchedule.ModelOccurrence(
                LocalDate.of(2026, 3, 10), due, "PENDING", 0, due, null));
    model.runScheduler();
    assertHeldAccount(source, 100, 100, 1);
    assertAccount(destination, 0, 0);

    model.advance(Duration.ofHours(1));
    assertAccount(source, 60, 2);
    assertAccount(destination, 40, 1);
    assertThat(model.holds().getFirst().state()).isEqualTo("EXPIRED");
    assertThat(schedule.status()).isEqualTo("COMPLETED");
    assertThat(schedule.occurrences())
        .containsExactly(
            new ModelSchedule.ModelOccurrence(
                LocalDate.of(2026, 3, 10), due, "POSTED", 0, due, null));
    model.runScheduler();
    assertAccount(source, 60, 2);
    assertAccount(destination, 40, 1);
  }

  @Test
  void scheduledTransferRetriesThenFails() {
    model.setNow(NOW);
    int source = account(0);
    int destination = account(0);
    Instant due = NOW.plus(Duration.ofHours(1));
    model.createSchedule("schedule", source, destination, 40, false, due);
    ModelSchedule schedule = model.schedules().getFirst();

    for (int attempt = 1; attempt <= 4; attempt++) {
      model.advance(Duration.ofHours(1));
      assertThat(schedule.occurrences()).hasSize(1);
      ModelSchedule.ModelOccurrence occurrence = schedule.occurrences().getFirst();
      assertThat(occurrence.attempts()).isEqualTo(attempt);
      assertThat(occurrence.dueAt()).isEqualTo(due);
      if (attempt <= 3) {
        assertThat(occurrence.state()).isEqualTo("PENDING");
        assertThat(occurrence.nextAttemptAt()).isEqualTo(due.plus(Duration.ofHours(attempt)));
        assertThat(occurrence.reason()).isNull();
        assertThat(schedule.status()).isEqualTo("ACTIVE");
        model.runScheduler();
        assertThat(schedule.occurrences().getFirst().attempts()).isEqualTo(attempt);
      } else {
        assertThat(occurrence.state()).isEqualTo("FAILED");
        assertThat(occurrence.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(schedule.status()).isEqualTo("COMPLETED");
      }
      assertAccount(source, 0, 0);
      assertAccount(destination, 0, 0);
    }
  }

  @Test
  void dailyScheduleCreatesNextOccurrence() {
    model.setNow(NOW);
    int source = account(0);
    int destination = account(0);
    Instant due = NOW.plus(Duration.ofHours(1)).plusNanos(123456000);
    model.deposit("deposit", source, 100);
    model.createSchedule("schedule", source, destination, 40, true, due);

    model.advance(Duration.between(NOW, due));
    ModelSchedule schedule = model.schedules().getFirst();
    Instant nextDue = due.plus(Duration.ofDays(1));
    assertThat(schedule.status()).isEqualTo("ACTIVE");
    assertThat(schedule.occurrences())
        .containsExactly(
            new ModelSchedule.ModelOccurrence(
                LocalDate.of(2026, 3, 10), due, "POSTED", 0, due, null),
            new ModelSchedule.ModelOccurrence(
                LocalDate.of(2026, 3, 11), nextDue, "PENDING", 0, nextDue, null));
    assertAccount(source, 60, 2);
    assertAccount(destination, 40, 1);
    model.advance(Duration.ofDays(1));
    assertThat(schedule.occurrences())
        .extracting(ModelSchedule.ModelOccurrence::state)
        .containsExactly("POSTED", "POSTED", "PENDING");
    assertAccount(source, 20, 3);
    assertAccount(destination, 80, 2);
  }

  @Test
  void paymentAcceptReducesBalance() {
    model.setNow(NOW);
    for (String behaviour : new String[] {"ACCEPT", "CONFIRM_TWICE"}) {
      int source = account(0);
      model.deposit("deposit-" + behaviour, source, 100);
      model.placeHold("hold-" + behaviour, source, 60, NOW.plusSeconds(60));

      Expected created = model.createPayment(behaviour, source, 30, behaviour);
      assertThat(created).isEqualTo(Expected.ok(202));
      int index = model.payments().size() - 1;
      assertThat(model.payments().get(index).state()).isEqualTo("INITIATED");
      assertHeldAccount(source, 100, 60, 1);
      assertThat(model.settlePayment(index)).isEqualTo("SETTLED");
      assertThat(model.payments().get(index).state()).isEqualTo("SETTLED");
      assertHeldAccount(source, 70, 60, 2);
      assertThat(model.replaySame(behaviour)).isSameAs(created);
      assertThat(model.payments()).hasSize(index + 1);
      assertThat(model.settlePayment(index)).isEqualTo("SETTLED");
      assertHeldAccount(source, 70, 60, 2);
    }
  }

  @Test
  void paymentRejectLeavesBalanceAndAddsTwoEntries() {
    model.setNow(NOW);
    int source = account(0);
    model.deposit("deposit", source, 100);
    model.placeHold("hold", source, 40, NOW.plusSeconds(60));

    assertThat(model.createPayment("payment", source, 50, "REJECT")).isEqualTo(Expected.ok(202));
    assertThat(model.settlePayment(0)).isEqualTo("REJECTED_COMPENSATED");
    assertThat(model.payments().getFirst().state()).isEqualTo("REJECTED_COMPENSATED");
    assertHeldAccount(source, 100, 40, 3);
    assertThat(model.settlePayment(0)).isEqualTo("REJECTED_COMPENSATED");
    assertHeldAccount(source, 100, 40, 3);
  }

  @Test
  void paymentWithoutFundsFails() {
    model.setNow(NOW);
    int source = account(0);

    assertThat(model.createPayment("payment", source, 1, "ACCEPT")).isEqualTo(Expected.ok(202));
    assertThat(model.payments().getFirst().state()).isEqualTo("INITIATED");
    assertThat(model.settlePayment(0)).isEqualTo("FAILED");
    assertAccount(source, 0, 0);
    model.deposit("deposit", source, 100);
    model.placeHold("hold", source, 80, NOW.plusSeconds(60));
    assertThat(model.createPayment("held-payment", source, 21, "REJECT"))
        .isEqualTo(Expected.ok(202));
    assertThat(model.settlePayment(1)).isEqualTo("FAILED");
    assertHeldAccount(source, 100, 80, 1);
    assertThat(model.settlePayment(0)).isEqualTo("FAILED");
    assertHeldAccount(source, 100, 80, 1);
  }

  @Test
  void closeBlockedWhileHoldActive() {
    model.setNow(NOW);
    int account = account(-500);
    model.placeHold("hold", account, 100, NOW.plusSeconds(60));

    assertThat(model.close(account)).isEqualTo(Expected.error(422, "ACCOUNT_NOT_EMPTY"));
    assertHeldAccount(account, 0, 100, 0);
    assertThat(model.releaseHold("release", 0)).isEqualTo(Expected.ok(200));
    assertThat(model.close(account)).isEqualTo(Expected.ok(200));
    assertAccount(account, 0, 0);
  }

  @Test
  void initiatedPaymentBlocksCloseUntilItFails() {
    int source = account(0);
    model.createPayment("payment", source, 1, "ACCEPT");

    assertThat(model.close(source)).isEqualTo(Expected.error(422, "ACCOUNT_NOT_EMPTY"));
    assertAccount(source, 0, 0);
    assertThat(model.settlePayment(0)).isEqualTo("FAILED");
    assertThat(model.close(source)).isEqualTo(Expected.ok(200));
  }

  @Test
  void frozenSourceFailsPaymentWithoutPosting() {
    int source = account(0);
    model.deposit("deposit", source, 100);
    model.createPayment("payment", source, 40, "ACCEPT");
    model.freeze(source);

    assertThat(model.settlePayment(0)).isEqualTo("FAILED");
    assertAccount(source, 100, 1);
    assertThat(model.createPayment("frozen", source, 40, "ACCEPT"))
        .isEqualTo(Expected.error(422, "ACCOUNT_FROZEN"));
    int closed = account(0);
    model.close(closed);
    assertThat(model.createPayment("closed", closed, 40, "ACCEPT"))
        .isEqualTo(Expected.error(422, "ACCOUNT_CLOSED"));
    assertThat(model.payments()).hasSize(1);
  }

  @Test
  void newRequestFingerprintsRejectChangedBodies() {
    model.setNow(NOW);
    int source = account(0);
    int destination = account(0);
    model.deposit("deposit", source, 100);
    Instant expiry = NOW.plusSeconds(60);
    model.placeHold("hold", source, 80, expiry);

    assertThat(model.placeHold("hold", source, 80, expiry.plusSeconds(1)))
        .isEqualTo(Expected.error(422, "IDEMPOTENCY_KEY_REUSED"));
    model.captureHold("capture", 0, 20, destination);
    assertThat(model.captureHold("capture", 0, 21, destination))
        .isEqualTo(Expected.error(422, "IDEMPOTENCY_KEY_REUSED"));
    model.placeHold("other", source, 10, expiry);
    model.releaseHold("release", 1);
    assertThat(model.releaseHold("release", 0))
        .isEqualTo(Expected.error(422, "IDEMPOTENCY_KEY_REUSED"));
    model.createSchedule("schedule", source, destination, 10, false, expiry);
    assertThat(model.createSchedule("schedule", source, destination, 10, true, expiry))
        .isEqualTo(Expected.error(422, "IDEMPOTENCY_KEY_REUSED"));
    model.createPayment("payment", source, 10, "ACCEPT");
    assertThat(model.createPayment("payment", source, 10, "REJECT"))
        .isEqualTo(Expected.error(422, "IDEMPOTENCY_KEY_REUSED"));
    assertAccount(source, 80, 2);
    assertAccount(destination, 20, 1);
    assertThat(model.holds()).hasSize(2);
    assertThat(model.schedules()).hasSize(1);
    assertThat(model.payments()).hasSize(1);
  }

  @Test
  void scheduleCreationChecksSourceBeforeDestination() {
    model.setNow(NOW);
    int destination = account(0);
    int source = account(0);
    model.freeze(source);
    model.close(destination);

    assertThat(
            model.createSchedule("schedule", source, destination, 10, false, NOW.plusSeconds(60)))
        .isEqualTo(Expected.error(422, "ACCOUNT_FROZEN"));
    assertThat(model.createSchedule("same", source, source, 10, false, NOW.plusSeconds(60)))
        .isEqualTo(Expected.error(422, "SAME_ACCOUNT"));
    assertThat(model.schedules()).isEmpty();
  }

  @Test
  void schedulerOrdersDueTimesAndFailsOnFrozenAccounts() {
    model.setNow(NOW);
    int source = account(0);
    int destination = account(0);
    model.deposit("deposit", source, 50);
    model.createSchedule("later", source, destination, 50, false, NOW.plusSeconds(120));
    model.createSchedule("earlier", source, destination, 50, false, NOW.plusSeconds(60));

    model.advance(Duration.ofSeconds(120));
    assertThat(model.schedules().getFirst().occurrences().getFirst().state()).isEqualTo("PENDING");
    assertThat(model.schedules().getFirst().occurrences().getFirst().attempts()).isEqualTo(1);
    assertThat(model.schedules().get(1).occurrences().getFirst().state()).isEqualTo("POSTED");
    assertAccount(source, 0, 2);
    assertAccount(destination, 50, 1);
    model.freeze(source);
    model.advance(Duration.ofHours(1));
    ModelSchedule schedule = model.schedules().getFirst();
    assertThat(schedule.status()).isEqualTo("COMPLETED");
    assertThat(schedule.occurrences().getFirst().state()).isEqualTo("FAILED");
    assertThat(schedule.occurrences().getFirst().attempts()).isEqualTo(1);
    assertThat(schedule.occurrences().getFirst().reason()).isEqualTo("ACCOUNT_FROZEN");
    assertAccount(source, 0, 2);
    assertAccount(destination, 50, 1);
  }

  @Test
  void schedulerSkipsMissedWindowsAndLimitsEachRun() {
    model.setNow(NOW);
    int source = account(0);
    int destination = account(0);
    Instant due = NOW.plusSeconds(60);
    model.createSchedule("schedule", source, destination, 10, true, due);

    model.setNow(due.plus(Duration.ofDays(30)));
    model.runScheduler();
    ModelSchedule schedule = model.schedules().getFirst();
    assertThat(schedule.occurrences()).hasSize(21);
    assertThat(schedule.occurrences().subList(0, 20))
        .allSatisfy(
            occurrence -> {
              assertThat(occurrence.state()).isEqualTo("SKIPPED");
              assertThat(occurrence.reason()).isEqualTo("MISSED_WINDOW");
              assertThat(occurrence.attempts()).isZero();
            });
    assertThat(schedule.occurrences().getLast().state()).isEqualTo("PENDING");
    assertThat(schedule.occurrences().getLast().dueAt()).isEqualTo(due.plus(Duration.ofDays(20)));
    assertAccount(source, 0, 0);
    assertAccount(destination, 0, 0);
  }

  private void assertHeldAccount(int account, long balance, long held, int entries) {
    assertThat(model.balance(account)).isEqualTo(balance);
    assertThat(model.held(account)).isEqualTo(held);
    assertThat(model.available(account)).isEqualTo(balance - held);
    assertThat(model.entryCount(account)).isEqualTo(entries);
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
