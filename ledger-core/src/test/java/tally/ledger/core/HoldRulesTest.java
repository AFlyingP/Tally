package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HoldRulesTest {

  private static final UUID ACCOUNT_ID = new UUID(0, 1);
  private static final Instant NOW = Instant.parse("2026-01-05T10:00:00Z");

  @Test
  void placeRejectsZeroAmount() {
    AccountState account = customer(AccountStatus.OPEN, 100, 101, 0);
    AccountState internal = internal(AccountKind.CLEARING, AccountStatus.CLOSED);

    assertRuleCode(RuleCode.INVALID_AMOUNT, () -> HoldRules.requirePlaceable(account, 0, NOW, NOW));
    assertRuleCode(
        RuleCode.INVALID_AMOUNT, () -> HoldRules.requirePlaceable(internal, -1, NOW, NOW));
  }

  @Test
  void placeRejectsInternalAccount() {
    AccountState account = internal(AccountKind.CLEARING, AccountStatus.CLOSED);

    assertRuleCode(
        RuleCode.INTERNAL_ACCOUNT, () -> HoldRules.requirePlaceable(account, 1, NOW, NOW));
  }

  @Test
  void placeRejectsFrozenAndClosed() {
    AccountState closed = customer(AccountStatus.CLOSED, 100, 0, 0);
    AccountState frozen = customer(AccountStatus.FROZEN, 100, 0, 0);

    assertRuleCode(RuleCode.ACCOUNT_CLOSED, () -> HoldRules.requirePlaceable(closed, 1, NOW, NOW));
    assertRuleCode(RuleCode.ACCOUNT_FROZEN, () -> HoldRules.requirePlaceable(frozen, 1, NOW, NOW));
  }

  @Test
  void placeRejectsPastExpiry() {
    AccountState account = customer(AccountStatus.OPEN, 0, 0, 0);

    assertRuleCode(RuleCode.INVALID_EXPIRY, () -> HoldRules.requirePlaceable(account, 1, NOW, NOW));
    assertRuleCode(
        RuleCode.INVALID_EXPIRY,
        () -> HoldRules.requirePlaceable(account, 1, NOW.minusNanos(1), NOW));
  }

  @Test
  void placeAcceptsExactlySevenDays() {
    AccountState account = customer(AccountStatus.OPEN, 100, 0, 0);

    assertThatCode(() -> HoldRules.requirePlaceable(account, 40, NOW.plus(Duration.ofDays(7)), NOW))
        .doesNotThrowAnyException();
    assertThatCode(() -> HoldRules.requirePlaceable(account, 1, NOW.plusNanos(1), NOW))
        .doesNotThrowAnyException();
  }

  @Test
  void placeRejectsSevenDaysPlusOneMicrosecond() {
    AccountState account = customer(AccountStatus.OPEN, 100, 0, 0);

    assertRuleCode(
        RuleCode.INVALID_EXPIRY,
        () ->
            HoldRules.requirePlaceable(
                account, 1, NOW.plus(Duration.ofDays(7)).plusNanos(1_000), NOW));
  }

  @Test
  void placeRejectsWhenAvailableWouldDropBelowLimit() {
    AccountState account = customer(AccountStatus.OPEN, 100, 60, 0);

    assertRuleCode(
        RuleCode.INSUFFICIENT_FUNDS,
        () -> HoldRules.requirePlaceable(account, 41, NOW.plus(Duration.ofDays(1)), NOW));
  }

  @Test
  void placeAcceptsDownToLimit() {
    AccountState account = customer(AccountStatus.OPEN, 100, 60, 0);

    assertThatCode(() -> HoldRules.requirePlaceable(account, 40, NOW.plus(Duration.ofDays(1)), NOW))
        .doesNotThrowAnyException();
  }

  @Test
  void captureRejectsNonActive() {
    for (HoldState state :
        new HoldState[] {HoldState.CAPTURED, HoldState.RELEASED, HoldState.EXPIRED}) {
      assertRuleCode(
          RuleCode.HOLD_NOT_ACTIVE, () -> HoldRules.requireCapturable(state, 10, 0, NOW, NOW));
    }
  }

  @Test
  void captureRejectsAtExpiryInstant() {
    assertRuleCode(
        RuleCode.HOLD_EXPIRED,
        () -> HoldRules.requireCapturable(HoldState.ACTIVE, 10, 0, NOW, NOW));
    assertRuleCode(
        RuleCode.HOLD_EXPIRED,
        () -> HoldRules.requireCapturable(HoldState.ACTIVE, 10, 1, NOW.minusNanos(1), NOW));
  }

  @Test
  void captureRejectsAboveHold() {
    Instant expiresAt = NOW.plus(Duration.ofDays(1));

    assertRuleCode(
        RuleCode.INVALID_AMOUNT,
        () -> HoldRules.requireCapturable(HoldState.ACTIVE, 10, 0, expiresAt, NOW));
    assertRuleCode(
        RuleCode.CAPTURE_EXCEEDS_HOLD,
        () -> HoldRules.requireCapturable(HoldState.ACTIVE, 10, 11, expiresAt, NOW));
  }

  @Test
  void captureAcceptsOneAndFullAmount() {
    Instant expiresAt = NOW.plus(Duration.ofDays(1));

    assertThatCode(() -> HoldRules.requireCapturable(HoldState.ACTIVE, 10, 1, expiresAt, NOW))
        .doesNotThrowAnyException();
    assertThatCode(() -> HoldRules.requireCapturable(HoldState.ACTIVE, 10, 10, expiresAt, NOW))
        .doesNotThrowAnyException();
  }

  @Test
  void releaseRejectsNonActive() {
    assertThatCode(() -> HoldRules.requireReleasable(HoldState.ACTIVE)).doesNotThrowAnyException();

    for (HoldState state :
        new HoldState[] {HoldState.CAPTURED, HoldState.RELEASED, HoldState.EXPIRED}) {
      assertRuleCode(RuleCode.HOLD_NOT_ACTIVE, () -> HoldRules.requireReleasable(state));
    }
  }

  private static AccountState customer(
      AccountStatus status, long balance, long held, long minBalance) {
    return new AccountState(
        ACCOUNT_ID, AccountKind.CUSTOMER, "USD", Side.CREDIT, minBalance, status, balance, held, 0);
  }

  private static AccountState internal(AccountKind kind, AccountStatus status) {
    return new AccountState(ACCOUNT_ID, kind, "USD", Side.DEBIT, null, status, 0, 0, 0);
  }

  private static void assertRuleCode(RuleCode code, Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            LedgerRuleException.class, exception -> assertThat(exception.code()).isEqualTo(code));
  }
}
