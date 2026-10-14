package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RecurrenceTest {

  @Test
  void onceHasNoNext() {
    assertThat(Recurrence.first(Frequency.ONCE, Instant.parse("2026-01-05T10:00:00Z"), null))
        .isEqualTo(LocalDate.parse("2026-01-05"));
    assertThat(Recurrence.next(Frequency.ONCE, LocalDate.parse("2026-01-05"), null, null))
        .isEmpty();
  }

  @Test
  void dailyAdvancesOneDay() {
    assertThat(Recurrence.next(Frequency.DAILY, LocalDate.parse("2026-01-05"), null, null))
        .contains(LocalDate.parse("2026-01-06"));
  }

  @Test
  void weeklyAdvancesSevenDays() {
    assertThat(Recurrence.next(Frequency.WEEKLY, LocalDate.parse("2026-01-05"), null, null))
        .contains(LocalDate.parse("2026-01-12"));
  }

  @Test
  void monthlyDay31RunsOnLastDayOfShortMonths() {
    LocalDate start = LocalDate.parse("2026-01-31");

    assertThat(
            Recurrence.first(Frequency.MONTHLY, start.atStartOfDay().toInstant(ZoneOffset.UTC), 31))
        .isEqualTo(start);
    assertThat(Recurrence.next(Frequency.MONTHLY, start, 31, null))
        .contains(LocalDate.parse("2026-02-28"));
    assertThat(Recurrence.next(Frequency.MONTHLY, LocalDate.parse("2026-02-28"), 31, null))
        .contains(LocalDate.parse("2026-03-31"));
    assertThat(Recurrence.next(Frequency.MONTHLY, LocalDate.parse("2026-03-31"), 31, null))
        .contains(LocalDate.parse("2026-04-30"));
  }

  @Test
  void monthlyDay1RunsOnFirstDayOfMonth() {
    assertThat(Recurrence.first(Frequency.MONTHLY, Instant.parse("2026-01-01T10:00:00Z"), 1))
        .isEqualTo(LocalDate.parse("2026-01-01"));
  }

  @Test
  void monthlyDay29InLeapYear() {
    assertThat(Recurrence.first(Frequency.MONTHLY, Instant.parse("2028-02-29T10:00:00Z"), 29))
        .isEqualTo(LocalDate.parse("2028-02-29"));
  }

  @Test
  void monthlyFirstMovesToNextMonthWhenDayPassed() {
    assertThat(Recurrence.first(Frequency.MONTHLY, Instant.parse("2026-01-20T10:00:00Z"), 15))
        .isEqualTo(LocalDate.parse("2026-02-15"));
  }

  @Test
  void monthlyFirstStaysInMonthWhenDayIsAfterStart() {
    assertThat(Recurrence.first(Frequency.MONTHLY, Instant.parse("2026-01-10T10:00:00Z"), 15))
        .isEqualTo(LocalDate.parse("2026-01-15"));
  }

  @Test
  void monthlyFirstIsStartDateWhenEqual() {
    assertThat(Recurrence.first(Frequency.MONTHLY, Instant.parse("2026-01-15T10:00:00Z"), 15))
        .isEqualTo(LocalDate.parse("2026-01-15"));
  }

  @Test
  void nextStopsAfterEndDate() {
    assertThat(
            Recurrence.next(
                Frequency.DAILY,
                LocalDate.parse("2026-01-05"),
                null,
                LocalDate.parse("2026-01-05")))
        .isEmpty();
  }

  @Test
  void nextAllowsEndDateItself() {
    assertThat(
            Recurrence.next(
                Frequency.DAILY,
                LocalDate.parse("2026-01-05"),
                null,
                LocalDate.parse("2026-01-06")))
        .contains(LocalDate.parse("2026-01-06"));
  }

  @Test
  void dueAtKeepsTimeOfDay() {
    assertThat(
            Recurrence.dueAt(
                LocalDate.parse("2026-02-01"), Instant.parse("2026-01-05T09:30:15.123456Z")))
        .isEqualTo(Instant.parse("2026-02-01T09:30:15.123456Z"));
  }

  @Test
  void monthlyWithoutDayThrows() {
    assertThatThrownBy(() -> Recurrence.first(Frequency.MONTHLY, Instant.EPOCH, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Recurrence.first(Frequency.MONTHLY, Instant.EPOCH, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Recurrence.first(Frequency.MONTHLY, Instant.EPOCH, 32))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
