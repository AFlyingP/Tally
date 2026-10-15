package tally.ledger.core;

import java.util.OptionalLong;

public final class PaymentFlow {

  private PaymentFlow() {}

  public static boolean allowed(PaymentState from, PaymentState to) {
    return switch (from) {
      case INITIATED -> to == PaymentState.HELD || to == PaymentState.FAILED;
      case HELD -> to == PaymentState.CAPTURED || to == PaymentState.FAILED;
      case CAPTURED -> to == PaymentState.SUBMITTING;
      case SUBMITTING ->
          to == PaymentState.SETTLED
              || to == PaymentState.REJECTED_COMPENSATED
              || to == PaymentState.UNKNOWN
              || to == PaymentState.MANUAL_REVIEW;
      case UNKNOWN ->
          to == PaymentState.SETTLED
              || to == PaymentState.REJECTED_COMPENSATED
              || to == PaymentState.MANUAL_REVIEW;
      case SETTLED, REJECTED_COMPENSATED, MANUAL_REVIEW, FAILED -> false;
    };
  }

  public static OptionalLong statusDelayMs(int attemptsSoFar, long[] backoffMs) {
    if (attemptsSoFar >= backoffMs.length) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(backoffMs[attemptsSoFar]);
  }
}
