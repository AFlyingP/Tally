package tally.ledger.core;

public final class Balances {

  private Balances() {}

  public static long apply(AccountState account, Side side, long amount) {
    if (side == account.normalSide()) {
      return Amounts.add(account.balance(), amount);
    }
    return Amounts.subtract(account.balance(), amount);
  }

  public static void requireWithinLimit(AccountState account, long newBalance, long newHeld) {
    if (account.kind() != AccountKind.CUSTOMER) {
      return;
    }
    long available = Amounts.subtract(newBalance, newHeld);
    if (available < account.minBalance()) {
      throw new LedgerRuleException(
          RuleCode.INSUFFICIENT_FUNDS, "available balance is below the account limit");
    }
  }

  public static void requirePostable(AccountState account, TransactionKind kind, String currency) {
    if (account.status() == AccountStatus.CLOSED) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_CLOSED, "account is closed");
    }
    if (account.status() == AccountStatus.FROZEN
        && (kind == TransactionKind.DEPOSIT
            || kind == TransactionKind.WITHDRAWAL
            || kind == TransactionKind.TRANSFER)) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_FROZEN, "account is frozen");
    }
    if (!account.currency().equals(currency)) {
      throw new LedgerRuleException(
          RuleCode.CURRENCY_MISMATCH, "posting currency does not match the account");
    }
  }
}
