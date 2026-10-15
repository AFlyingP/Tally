package tally.ledger.core;

public enum PaymentState {
  INITIATED,
  HELD,
  CAPTURED,
  SUBMITTING,
  UNKNOWN,
  SETTLED,
  REJECTED_COMPENSATED,
  MANUAL_REVIEW,
  FAILED;

  public boolean terminal() {
    return switch (this) {
      case SETTLED, REJECTED_COMPENSATED, MANUAL_REVIEW, FAILED -> true;
      case INITIATED, HELD, CAPTURED, SUBMITTING, UNKNOWN -> false;
    };
  }

  public boolean inClearing() {
    return switch (this) {
      case CAPTURED, SUBMITTING, UNKNOWN, MANUAL_REVIEW -> true;
      case INITIATED, HELD, SETTLED, REJECTED_COMPENSATED, FAILED -> false;
    };
  }
}
