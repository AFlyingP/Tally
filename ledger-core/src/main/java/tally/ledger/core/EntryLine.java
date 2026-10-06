package tally.ledger.core;

import java.util.UUID;

public record EntryLine(UUID accountId, Side side, long amount) {

  public EntryLine {
    Amounts.requirePositive(amount);
  }
}
