package tally.platform.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tally.platform.testing.MutableClock;

class UuidV7Test {

  @Test
  void idsAreVersionSevenAndOrderedByTime() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-05T10:00:00Z"));

    UUID first = UuidV7.next(clock);
    clock.advance(Duration.ofMillis(1));
    UUID second = UuidV7.next(clock);

    assertThat(first.version()).isEqualTo(7);
    assertThat(first.variant()).isEqualTo(2);
    assertThat(second.version()).isEqualTo(7);
    assertThat(first.toString()).isLessThan(second.toString());
  }

  @Test
  void worksWithAClockThatTicksInMicroseconds() {
    Clock micros = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000));

    UUID id = UuidV7.next(micros);

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.getMostSignificantBits() >>> 16)
        .isCloseTo(micros.instant().toEpochMilli(), within(60_000L));
  }

  @Test
  void clockReportsMicrosecondsAndCanBeSet() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-05T10:00:00.123456789Z"));
    assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-05T10:00:00.123456Z"));

    clock.set(Instant.parse("2026-02-01T00:00:00Z"));
    assertThat(clock.millis()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z").toEpochMilli());
  }
}
