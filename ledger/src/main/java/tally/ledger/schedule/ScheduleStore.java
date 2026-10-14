package tally.ledger.schedule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.ledger.core.Frequency;

@Component
public class ScheduleStore {

  public record ScheduleRow(
      UUID id,
      UUID sourceAccountId,
      UUID destinationAccountId,
      String currency,
      long amount,
      Frequency frequency,
      Instant startAt,
      Integer dayOfMonth,
      LocalDate endDate,
      String status,
      String description,
      long keyId,
      String clientId,
      Instant createdAt,
      Instant updatedAt) {}

  public record OccurrenceRow(
      UUID id,
      UUID scheduleId,
      LocalDate occurrenceDate,
      Instant dueAt,
      String state,
      int attempts,
      Instant nextAttemptAt,
      UUID transactionId,
      String reason,
      Instant updatedAt) {}

  public record DueOccurrence(
      UUID id,
      UUID scheduleId,
      LocalDate occurrenceDate,
      Instant dueAt,
      String state,
      int attempts,
      Instant nextAttemptAt,
      UUID transactionId,
      String reason,
      Instant updatedAt,
      UUID sourceAccountId,
      UUID destinationAccountId,
      String currency,
      long amount,
      Frequency frequency,
      Instant startAt,
      Integer dayOfMonth,
      LocalDate endDate,
      String description) {}

  public void insert(Connection connection, ScheduleRow schedule) throws SQLException {
    String sql =
        """
        INSERT INTO ledger.schedule (id, source_account_id, destination_account_id, currency,
          amount, frequency, start_at, day_of_month, end_date, status, description,
          idempotency_key_id, client_id, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, schedule.id());
      insert.setObject(2, schedule.sourceAccountId());
      insert.setObject(3, schedule.destinationAccountId());
      insert.setString(4, schedule.currency());
      insert.setLong(5, schedule.amount());
      insert.setString(6, schedule.frequency().name());
      insert.setObject(7, time(schedule.startAt()));
      insert.setObject(8, schedule.dayOfMonth());
      insert.setObject(9, schedule.endDate());
      insert.setString(10, schedule.status());
      insert.setString(11, schedule.description());
      insert.setLong(12, schedule.keyId());
      insert.setString(13, schedule.clientId());
      insert.setObject(14, time(schedule.createdAt()));
      insert.setObject(15, time(schedule.updatedAt()));
      insert.executeUpdate();
    }
  }

  public Optional<ScheduleRow> find(Connection connection, UUID id) throws SQLException {
    String sql = "SELECT * FROM ledger.schedule WHERE id = ?";
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(schedule(row)) : Optional.empty();
      }
    }
  }

  public int cancel(Connection connection, UUID id, Instant now) throws SQLException {
    String sql =
        "UPDATE ledger.schedule SET status = 'CANCELLED', updated_at = ?"
            + " WHERE id = ? AND status = 'ACTIVE'";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setObject(1, time(now));
      update.setObject(2, id);
      return update.executeUpdate();
    }
  }

  public void insertOccurrence(
      Connection connection, UUID id, UUID scheduleId, LocalDate date, Instant dueAt, Instant now)
      throws SQLException {
    String sql =
        """
        INSERT INTO ledger.schedule_occurrence (id, schedule_id, occurrence_date, due_at,
          state, attempts, next_attempt_at, transaction_id, reason, updated_at)
        VALUES (?, ?, ?, ?, 'PENDING', 0, ?, NULL, NULL, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, id);
      insert.setObject(2, scheduleId);
      insert.setObject(3, date);
      insert.setObject(4, time(dueAt));
      insert.setObject(5, time(dueAt));
      insert.setObject(6, time(now));
      insert.executeUpdate();
    }
  }

  public int skipPending(Connection connection, UUID scheduleId, String reason, Instant now)
      throws SQLException {
    String sql =
        "UPDATE ledger.schedule_occurrence SET state = 'SKIPPED', reason = ?, updated_at = ?"
            + " WHERE schedule_id = ? AND state = 'PENDING'";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setString(1, reason);
      update.setObject(2, time(now));
      update.setObject(3, scheduleId);
      return update.executeUpdate();
    }
  }

  public List<OccurrenceRow> occurrences(Connection connection, UUID scheduleId, int limit)
      throws SQLException {
    String sql =
        "SELECT * FROM ledger.schedule_occurrence WHERE schedule_id = ?"
            + " ORDER BY occurrence_date DESC LIMIT ?";
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, scheduleId);
      select.setInt(2, limit);
      try (ResultSet rows = select.executeQuery()) {
        List<OccurrenceRow> result = new ArrayList<>();
        while (rows.next()) {
          result.add(occurrence(rows));
        }
        return result;
      }
    }
  }

  public Optional<DueOccurrence> claimDue(Connection connection, Instant now) throws SQLException {
    String sql =
        """
        SELECT o.*, s.source_account_id, s.destination_account_id, s.currency, s.amount,
          s.frequency, s.start_at, s.day_of_month, s.end_date, s.description
        FROM ledger.schedule_occurrence o JOIN ledger.schedule s ON s.id = o.schedule_id
        WHERE o.state = 'PENDING' AND o.next_attempt_at <= ? AND s.status = 'ACTIVE'
        ORDER BY o.due_at, o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, time(now));
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new DueOccurrence(
                row.getObject("id", UUID.class),
                row.getObject("schedule_id", UUID.class),
                row.getObject("occurrence_date", LocalDate.class),
                instant(row, "due_at"),
                row.getString("state"),
                row.getInt("attempts"),
                instant(row, "next_attempt_at"),
                row.getObject("transaction_id", UUID.class),
                row.getString("reason"),
                instant(row, "updated_at"),
                row.getObject("source_account_id", UUID.class),
                row.getObject("destination_account_id", UUID.class),
                row.getString("currency"),
                row.getLong("amount"),
                Frequency.valueOf(row.getString("frequency")),
                instant(row, "start_at"),
                row.getObject("day_of_month", Integer.class),
                row.getObject("end_date", LocalDate.class),
                row.getString("description")));
      }
    }
  }

  public void markPosted(Connection connection, UUID occurrenceId, UUID transactionId, Instant now)
      throws SQLException {
    String sql =
        "UPDATE ledger.schedule_occurrence SET state = 'POSTED', transaction_id = ?,"
            + " reason = NULL, updated_at = ? WHERE id = ?";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setObject(1, transactionId);
      update.setObject(2, time(now));
      update.setObject(3, occurrenceId);
      update.executeUpdate();
    }
  }

  public void markRetry(
      Connection connection, UUID occurrenceId, int attempts, Instant nextAttemptAt, Instant now)
      throws SQLException {
    String sql =
        "UPDATE ledger.schedule_occurrence SET attempts = ?, next_attempt_at = ?, updated_at = ?"
            + " WHERE id = ?";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setInt(1, attempts);
      update.setObject(2, time(nextAttemptAt));
      update.setObject(3, time(now));
      update.setObject(4, occurrenceId);
      update.executeUpdate();
    }
  }

  public void markTerminal(
      Connection connection,
      UUID occurrenceId,
      String state,
      String reason,
      int attempts,
      Instant now)
      throws SQLException {
    String sql =
        "UPDATE ledger.schedule_occurrence SET state = ?, reason = ?, attempts = ?, updated_at = ?"
            + " WHERE id = ?";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setString(1, state);
      update.setString(2, reason);
      update.setInt(3, attempts);
      update.setObject(4, time(now));
      update.setObject(5, occurrenceId);
      update.executeUpdate();
    }
  }

  public int complete(Connection connection, UUID scheduleId, Instant now) throws SQLException {
    String sql =
        "UPDATE ledger.schedule SET status = 'COMPLETED', updated_at = ?"
            + " WHERE id = ? AND status = 'ACTIVE'";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setObject(1, time(now));
      update.setObject(2, scheduleId);
      return update.executeUpdate();
    }
  }

  private static ScheduleRow schedule(ResultSet row) throws SQLException {
    return new ScheduleRow(
        row.getObject("id", UUID.class),
        row.getObject("source_account_id", UUID.class),
        row.getObject("destination_account_id", UUID.class),
        row.getString("currency"),
        row.getLong("amount"),
        Frequency.valueOf(row.getString("frequency")),
        instant(row, "start_at"),
        row.getObject("day_of_month", Integer.class),
        row.getObject("end_date", LocalDate.class),
        row.getString("status"),
        row.getString("description"),
        row.getLong("idempotency_key_id"),
        row.getString("client_id"),
        instant(row, "created_at"),
        instant(row, "updated_at"));
  }

  private static OccurrenceRow occurrence(ResultSet row) throws SQLException {
    return new OccurrenceRow(
        row.getObject("id", UUID.class),
        row.getObject("schedule_id", UUID.class),
        row.getObject("occurrence_date", LocalDate.class),
        instant(row, "due_at"),
        row.getString("state"),
        row.getInt("attempts"),
        instant(row, "next_attempt_at"),
        row.getObject("transaction_id", UUID.class),
        row.getString("reason"),
        instant(row, "updated_at"));
  }

  private static OffsetDateTime time(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }
}
