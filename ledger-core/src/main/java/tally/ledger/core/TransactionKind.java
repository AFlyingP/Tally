package tally.ledger.core;

public enum TransactionKind {
  DEPOSIT,
  WITHDRAWAL,
  TRANSFER,
  REVERSAL,
  HOLD_CAPTURE,
  EXTERNAL_SETTLEMENT,
  EXTERNAL_COMPENSATION
}
