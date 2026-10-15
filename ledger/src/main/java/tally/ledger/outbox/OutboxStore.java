package tally.ledger.outbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** SQL for outbox rows. Every method runs on the caller's connection. */
@Component
public class OutboxStore {

  public record Pending(
      long id, UUID eventId, UUID keyAccountId, String requestId, String payload) {}

  public List<Pending> unpublished(Connection connection, int limit) throws SQLException {
    String sql =
        """
        SELECT id, event_id, key_account_id, request_id, payload
        FROM ledger.outbox WHERE published_at IS NULL ORDER BY id LIMIT ?
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setInt(1, limit);
      try (ResultSet rows = select.executeQuery()) {
        List<Pending> pending = new ArrayList<>();
        while (rows.next()) {
          pending.add(
              new Pending(
                  rows.getLong("id"),
                  rows.getObject("event_id", UUID.class),
                  rows.getObject("key_account_id", UUID.class),
                  rows.getString("request_id"),
                  rows.getString("payload")));
        }
        return pending;
      }
    }
  }

  public void markPublished(Connection connection, List<Long> ids, Instant now)
      throws SQLException {
    var array = connection.createArrayOf("bigint", ids.toArray(Long[]::new));
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE ledger.outbox SET published_at = ? WHERE id = ANY(?)")) {
      update.setObject(1, now.atOffset(ZoneOffset.UTC));
      update.setArray(2, array);
      update.executeUpdate();
    } finally {
      array.free();
    }
  }

  public Set<UUID> publishedEventIds(Connection connection) throws SQLException {
    return eventIds(
        connection, "SELECT event_id FROM ledger.outbox WHERE published_at IS NOT NULL");
  }

  public Set<UUID> allEventIds(Connection connection) throws SQLException {
    return eventIds(connection, "SELECT event_id FROM ledger.outbox");
  }

  public long unpublishedCount(Connection connection) throws SQLException {
    try (PreparedStatement select =
            connection.prepareStatement(
                "SELECT count(*) FROM ledger.outbox WHERE published_at IS NULL");
        ResultSet row = select.executeQuery()) {
      row.next();
      return row.getLong(1);
    }
  }

  private Set<UUID> eventIds(Connection connection, String sql) throws SQLException {
    try (PreparedStatement select = connection.prepareStatement(sql);
        ResultSet rows = select.executeQuery()) {
      Set<UUID> ids = new HashSet<>();
      while (rows.next()) {
        ids.add(rows.getObject("event_id", UUID.class));
      }
      return ids;
    }
  }

  public void insert(
      Connection connection,
      UUID eventId,
      UUID transactionId,
      UUID keyAccountId,
      String requestId,
      String payload,
      Instant createdAt)
      throws SQLException {
    String sql =
        """
        INSERT INTO ledger.outbox
          (event_id, transaction_id, key_account_id, request_id, payload, created_at, published_at)
        VALUES (?, ?, ?, ?, ?, ?, NULL)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, eventId);
      insert.setObject(2, transactionId);
      insert.setObject(3, keyAccountId);
      insert.setString(4, requestId);
      insert.setString(5, payload);
      insert.setObject(6, createdAt.atOffset(ZoneOffset.UTC));
      insert.executeUpdate();
    }
  }
}
