package tally.ledger.core;

import java.util.random.RandomGenerator;

public final class Backoff {

  private Backoff() {}

  public static long delayMs(int failedAttempts, long baseMs, long capMs, RandomGenerator random) {
    if (failedAttempts < 1) {
      throw new IllegalArgumentException("failed attempts must be at least one");
    }
    long cap = Math.min(capMs, baseMs << Math.min(failedAttempts - 1, 30));
    return random.nextLong(0, cap + 1);
  }
}
