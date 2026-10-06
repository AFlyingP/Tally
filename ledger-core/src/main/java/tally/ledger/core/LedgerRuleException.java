package tally.ledger.core;

public final class LedgerRuleException extends RuntimeException {

  private final RuleCode code;

  public LedgerRuleException(RuleCode code, String message) {
    super(message);
    this.code = code;
  }

  public RuleCode code() {
    return code;
  }
}
