package tally.ledger.idempotency;

public record Outcome(int status, Object body) {}
