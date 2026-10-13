package tally.ledger.holds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.ledger.core.HoldState;

@Component
public class HoldStore {

  public record HoldRow(
      UUID id,
      UUID accountId,
      String currency,
      long amount,
      HoldState state,
      Instant expiresAt,
      Long capturedAmount,
      UUID captureTransactionId,
      long keyId,
      String clientId,
      Instant createdAt) {}

  public void insert(Connection connection, HoldRow hold) throws SQLException {
    String sql =
        """
        INSERT INTO ledger.hold (id, account_id, currency, amount, state, expires_at,
          captured_amount, capture_transaction_id, idempotency_key_id, client_id, created_at,
          updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, hold.id());
      insert.setObject(2, hold.accountId());
      insert.setString(3, hold.currency());
      insert.setLong(4, hold.amount());
      insert.setString(5, hold.state().name());
      insert.setObject(6, time(hold.expiresAt()));
      insert.setObject(7, hold.capturedAmount());
      insert.setObject(8, hold.captureTransactionId());
      insert.setLong(9, hold.keyId());
      insert.setString(10, hold.clientId());
      insert.setObject(11, time(hold.createdAt()));
      insert.setObject(12, time(hold.createdAt()));
      insert.executeUpdate();
    }
  }

  public Optional<HoldRow> find(Connection connection, UUID id) throws SQLException {
    String sql =
        """
        SELECT id, account_id, currency, amount, state, expires_at, captured_amount,
          capture_transaction_id, idempotency_key_id, client_id, created_at
        FROM ledger.hold WHERE id = ?
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new HoldRow(
                row.getObject("id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getString("currency"),
                row.getLong("amount"),
                HoldState.valueOf(row.getString("state")),
                instant(row, "expires_at"),
                row.getObject("captured_amount", Long.class),
                row.getObject("capture_transaction_id", UUID.class),
                row.getLong("idempotency_key_id"),
                row.getString("client_id"),
                instant(row, "created_at")));
      }
    }
  }

  public int markCaptured(
      Connection connection, UUID id, long capturedAmount, UUID transactionId, Instant now)
      throws SQLException {
    String sql =
        """
        UPDATE ledger.hold SET state = 'CAPTURED', captured_amount = ?,
          capture_transaction_id = ?, updated_at = ?
        WHERE id = ? AND state = 'ACTIVE'
        """;
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setLong(1, capturedAmount);
      update.setObject(2, transactionId);
      update.setObject(3, time(now));
      update.setObject(4, id);
      return update.executeUpdate();
    }
  }

  public int markReleased(Connection connection, UUID id, Instant now) throws SQLException {
    String sql =
        "UPDATE ledger.hold SET state = 'RELEASED', updated_at = ? WHERE id = ? AND state = 'ACTIVE'";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setObject(1, time(now));
      update.setObject(2, id);
      return update.executeUpdate();
    }
  }

  public int markExpired(Connection connection, UUID id, Instant now) throws SQLException {
    String sql =
        """
        UPDATE ledger.hold SET state = 'EXPIRED', updated_at = ?
        WHERE id = ? AND state = 'ACTIVE' AND expires_at <= ?
        """;
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setObject(1, time(now));
      update.setObject(2, id);
      update.setObject(3, time(now));
      return update.executeUpdate();
    }
  }

  public void insertEvent(
      Connection connection,
      UUID holdId,
      String type,
      long amount,
      UUID transactionId,
      String actor,
      Instant now,
      UUID auditId)
      throws SQLException {
    String sql =
        """
        INSERT INTO ledger.hold_event (hold_id, type, amount, transaction_id, actor, occurred_at,
          audit_id)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, holdId);
      insert.setString(2, type);
      insert.setLong(3, amount);
      insert.setObject(4, transactionId);
      insert.setString(5, actor);
      insert.setObject(6, time(now));
      insert.setObject(7, auditId);
      insert.executeUpdate();
    }
  }

  public List<UUID> due(Connection connection, Instant now, int limit) throws SQLException {
    String sql =
        """
        SELECT id FROM ledger.hold
        WHERE state = 'ACTIVE' AND expires_at <= ?
        ORDER BY expires_at, id LIMIT ?
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, time(now));
      select.setInt(2, limit);
      try (ResultSet rows = select.executeQuery()) {
        List<UUID> ids = new ArrayList<>();
        while (rows.next()) {
          ids.add(rows.getObject("id", UUID.class));
        }
        return ids;
      }
    }
  }

  private static OffsetDateTime time(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }
}
