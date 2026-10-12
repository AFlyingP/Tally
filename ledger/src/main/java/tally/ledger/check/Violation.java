package tally.ledger.check;

public record Violation(String invariant, String subject, String detail) {

  public String line() {
    return "%s %s %s".formatted(invariant, subject, detail);
  }
}
