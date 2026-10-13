package tally.ledger.core;

import java.time.Duration;
import java.time.Instant;

public final class HoldRules {

  private static final Duration MAX_EXPIRY = Duration.ofDays(7);

  private HoldRules() {}

  public static void requirePlaceable(
      AccountState account, long amount, Instant expiresAt, Instant now) {
    Amounts.requirePositive(amount);
    if (account.kind() != AccountKind.CUSTOMER) {
      throw new LedgerRuleException(RuleCode.INTERNAL_ACCOUNT, "holds require a customer account");
    }
    if (account.status() == AccountStatus.CLOSED) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_CLOSED, "account is closed");
    }
    if (account.status() == AccountStatus.FROZEN) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_FROZEN, "account is frozen");
    }
    if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_EXPIRY))) {
      throw new LedgerRuleException(
          RuleCode.INVALID_EXPIRY, "hold expiry is outside the allowed range");
    }
    Balances.requireWithinLimit(account, account.balance(), Amounts.add(account.held(), amount));
  }

  public static void requireCapturable(
      HoldState state, long holdAmount, long captureAmount, Instant expiresAt, Instant now) {
    if (state != HoldState.ACTIVE) {
      throw new LedgerRuleException(RuleCode.HOLD_NOT_ACTIVE, "hold is not active");
    }
    if (!now.isBefore(expiresAt)) {
      throw new LedgerRuleException(RuleCode.HOLD_EXPIRED, "hold has expired");
    }
    Amounts.requirePositive(captureAmount);
    if (captureAmount > holdAmount) {
      throw new LedgerRuleException(RuleCode.CAPTURE_EXCEEDS_HOLD, "capture exceeds hold amount");
    }
  }

  public static void requireReleasable(HoldState state) {
    if (state != HoldState.ACTIVE) {
      throw new LedgerRuleException(RuleCode.HOLD_NOT_ACTIVE, "hold is not active");
    }
  }
}
