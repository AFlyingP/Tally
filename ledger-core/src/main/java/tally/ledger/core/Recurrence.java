package tally.ledger.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

public final class Recurrence {

  private Recurrence() {}

  public static LocalDate first(Frequency frequency, Instant startAt, Integer dayOfMonth) {
    LocalDate startDate = startAt.atZone(ZoneOffset.UTC).toLocalDate();
    if (frequency != Frequency.MONTHLY) {
      return startDate;
    }
    int day = requireMonthlyDay(dayOfMonth);
    YearMonth month = YearMonth.from(startDate);
    LocalDate candidate = month.atDay(Math.min(day, month.lengthOfMonth()));
    return candidate.isBefore(startDate) ? monthlyDate(month.plusMonths(1), day) : candidate;
  }

  public static Optional<LocalDate> next(
      Frequency frequency, LocalDate previous, Integer dayOfMonth, LocalDate endDate) {
    LocalDate nextDate =
        switch (frequency) {
          case ONCE -> null;
          case DAILY -> previous.plusDays(1);
          case WEEKLY -> previous.plusDays(7);
          case MONTHLY -> monthlyDate(YearMonth.from(previous).plusMonths(1), dayOfMonth);
        };
    if (nextDate == null || (endDate != null && nextDate.isAfter(endDate))) {
      return Optional.empty();
    }
    return Optional.of(nextDate);
  }

  public static Instant dueAt(LocalDate date, Instant startAt) {
    LocalTime time = startAt.atZone(ZoneOffset.UTC).toLocalTime();
    return date.atTime(time).toInstant(ZoneOffset.UTC);
  }

  private static int requireMonthlyDay(Integer dayOfMonth) {
    if (dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31) {
      throw new IllegalArgumentException("day of month must be between 1 and 31");
    }
    return dayOfMonth;
  }

  private static LocalDate monthlyDate(YearMonth month, int dayOfMonth) {
    return month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
  }
}
