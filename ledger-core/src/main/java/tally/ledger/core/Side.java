package tally.ledger.core;

/** The side of an account an entry is booked on. */
public enum Side {
  DEBIT,
  CREDIT;

  public Side opposite() {
    return this == DEBIT ? CREDIT : DEBIT;
  }
}
