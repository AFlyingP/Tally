package tally.monitor.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MovementStore {

  public boolean markProcessed(Connection connection, UUID eventId, Instant now)
      throws SQLException {
    String sql =
        "INSERT INTO monitor.processed_event (event_id, processed_at) VALUES (?, ?)"
            + " ON CONFLICT DO NOTHING";
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, eventId);
      insert.setObject(2, timestamp(now));
      return insert.executeUpdate() == 1;
    }
  }

  public boolean insert(Connection connection, UUID eventId, Movement movement)
      throws SQLException {
    String sql =
        "INSERT INTO monitor.movement"
            + " (event_id, transaction_id, account_id, direction, kind, amount, currency,"
            + " counted, r2_candidate, event_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (transaction_id, account_id) DO NOTHING";
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, eventId);
      insert.setObject(2, movement.transactionId());
      insert.setObject(3, movement.accountId());
      insert.setString(4, movement.direction());
      insert.setString(5, movement.kind());
      insert.setLong(6, movement.amount());
      insert.setString(7, movement.currency());
      insert.setBoolean(8, movement.counted());
      insert.setBoolean(9, movement.r2Candidate());
      insert.setObject(10, timestamp(movement.eventTime()));
      return insert.executeUpdate() == 1;
    }
  }

  public List<Movement> window(
      Connection connection,
      UUID accountId,
      Instant shortFrom,
      Instant shortTo,
      Instant r2From,
      Instant r2To)
      throws SQLException {
    String sql =
        "SELECT transaction_id, account_id, direction, kind, amount, currency, counted,"
            + " r2_candidate, event_time FROM monitor.movement WHERE account_id = ?"
            + " AND ((event_time BETWEEN ? AND ?)"
            + " OR (r2_candidate AND event_time BETWEEN ? AND ?))"
            + " ORDER BY event_time, id";
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, accountId);
      select.setObject(2, timestamp(shortFrom));
      select.setObject(3, timestamp(shortTo));
      select.setObject(4, timestamp(r2From));
      select.setObject(5, timestamp(r2To));
      try (ResultSet rows = select.executeQuery()) {
        List<Movement> movements = new ArrayList<>();
        while (rows.next()) {
          movements.add(
              new Movement(
                  rows.getObject("transaction_id", UUID.class),
                  rows.getObject("account_id", UUID.class),
                  rows.getString("direction"),
                  rows.getString("kind"),
                  rows.getLong("amount"),
                  rows.getString("currency"),
                  rows.getBoolean("counted"),
                  rows.getBoolean("r2_candidate"),
                  rows.getObject("event_time", java.time.OffsetDateTime.class).toInstant()));
        }
        return List.copyOf(movements);
      }
    }
  }

  private static java.time.OffsetDateTime timestamp(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }
}
