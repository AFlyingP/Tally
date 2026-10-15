package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentFlowTest {

  private static final long[] BACKOFF_MS = {
    5000, 10000, 20000, 40000, 75000, 120000, 150000, 180000
  };

  @Test
  void allowsExactlyTheTwelveTransitions() {
    Set<String> allowed =
        Set.of(
            "INITIATED:HELD",
            "INITIATED:FAILED",
            "HELD:CAPTURED",
            "HELD:FAILED",
            "CAPTURED:SUBMITTING",
            "SUBMITTING:SETTLED",
            "SUBMITTING:REJECTED_COMPENSATED",
            "SUBMITTING:UNKNOWN",
            "SUBMITTING:MANUAL_REVIEW",
            "UNKNOWN:SETTLED",
            "UNKNOWN:REJECTED_COMPENSATED",
            "UNKNOWN:MANUAL_REVIEW");

    for (PaymentState from : PaymentState.values()) {
      for (PaymentState to : PaymentState.values()) {
        assertThat(PaymentFlow.allowed(from, to))
            .as("%s to %s", from, to)
            .isEqualTo(allowed.contains(from + ":" + to));
      }
    }
  }

  @Test
  void terminalStatesAllowNothing() {
    Set<PaymentState> terminalStates =
        Set.of(
            PaymentState.SETTLED,
            PaymentState.REJECTED_COMPENSATED,
            PaymentState.MANUAL_REVIEW,
            PaymentState.FAILED);

    for (PaymentState from : PaymentState.values()) {
      assertThat(from.terminal()).as("%s", from).isEqualTo(terminalStates.contains(from));
      if (terminalStates.contains(from)) {
        for (PaymentState to : PaymentState.values()) {
          assertThat(PaymentFlow.allowed(from, to)).as("%s to %s", from, to).isFalse();
        }
      }
    }
  }

  @Test
  void inClearingStates() {
    for (PaymentState state : PaymentState.values()) {
      assertThat(state.inClearing())
          .as("%s", state)
          .isEqualTo(
              state == PaymentState.CAPTURED
                  || state == PaymentState.SUBMITTING
                  || state == PaymentState.UNKNOWN
                  || state == PaymentState.MANUAL_REVIEW);
    }
  }

  @Test
  void statusDelayFollowsBackoffThenEnds() {
    for (int attemptsSoFar = 0; attemptsSoFar < BACKOFF_MS.length; attemptsSoFar++) {
      assertThat(PaymentFlow.statusDelayMs(attemptsSoFar, BACKOFF_MS))
          .hasValue(BACKOFF_MS[attemptsSoFar]);
    }
    assertThat(PaymentFlow.statusDelayMs(BACKOFF_MS.length, BACKOFF_MS)).isEmpty();
  }
}
