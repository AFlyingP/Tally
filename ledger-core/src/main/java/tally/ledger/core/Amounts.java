package tally.ledger.core;

public final class Amounts {

  private Amounts() {}

  public static long add(long a, long b) {
    try {
      return Math.addExact(a, b);
    } catch (ArithmeticException e) {
      throw overflow();
    }
  }

  public static long subtract(long a, long b) {
    try {
      return Math.subtractExact(a, b);
    } catch (ArithmeticException e) {
      throw overflow();
    }
  }

  public static long negate(long amount) {
    try {
      return Math.negateExact(amount);
    } catch (ArithmeticException e) {
      throw overflow();
    }
  }

  public static void requirePositive(long amount) {
    if (amount <= 0) {
      throw new LedgerRuleException(RuleCode.INVALID_AMOUNT, "amount must be positive");
    }
  }

  private static LedgerRuleException overflow() {
    return new LedgerRuleException(RuleCode.AMOUNT_OVERFLOW, "amount overflow");
  }
}
