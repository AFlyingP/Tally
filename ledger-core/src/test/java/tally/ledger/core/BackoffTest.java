package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BackoffTest {

  @Test
  void delayStaysWithinDoublingBound() {
    Random random = new Random(20260101L);
    boolean fourthAttemptExceededFour = false;

    for (int failedAttempts = 1; failedAttempts <= 12; failedAttempts++) {
      long maximum = Math.min(200, 1L << (failedAttempts - 1));
      for (int draw = 0; draw < 1000; draw++) {
        long delay = Backoff.delayMs(failedAttempts, 1, 200, random);
        assertThat(delay).isBetween(0L, maximum);
        if (failedAttempts == 4 && delay > 4) {
          fourthAttemptExceededFour = true;
        }
      }
    }

    assertThat(fourthAttemptExceededFour).isTrue();
    assertThatThrownBy(() -> Backoff.delayMs(0, 1, 200, random))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Backoff.delayMs(1000, 1, 200, random)).isBetween(0L, 200L);
  }
}
