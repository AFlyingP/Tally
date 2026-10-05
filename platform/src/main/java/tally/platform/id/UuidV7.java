package tally.platform.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** Generates UUID version 7 values, which sort by creation time. */
public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {}

  public static UUID next(Clock clock) {
    long high = (clock.millis() << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
    long low = 0x8000000000000000L | (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL);
    return new UUID(high, low);
  }
}
