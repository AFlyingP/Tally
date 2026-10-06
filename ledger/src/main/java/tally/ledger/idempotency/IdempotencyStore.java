package tally.ledger.idempotency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;

/** SQL for idempotency keys. Every method runs on the caller's connection. */
@Component
public class IdempotencyStore {

  public record Stored(long id, byte[] requestHash, int statusCode, String responseBody) {}

  public OptionalLong claim(
      Connection connection, String clientId, String key, byte[] requestHash, Instant now)
      throws SQLException {
    String sql =
        """
        INSERT INTO ledger.idempotency_key
          (client_id, key, request_hash, status_code, response_body, created_at)
        VALUES (?, ?, ?, 0, '', ?)
        ON CONFLICT (client_id, key) DO NOTHING RETURNING id
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setString(1, clientId);
      insert.setString(2, key);
      insert.setBytes(3, requestHash);
      insert.setObject(4, now.atOffset(ZoneOffset.UTC));
      try (ResultSet row = insert.executeQuery()) {
        return row.next() ? OptionalLong.of(row.getLong("id")) : OptionalLong.empty();
      }
    }
  }

  public Optional<Stored> find(Connection connection, String clientId, String key)
      throws SQLException {
    String sql =
        """
        SELECT id, request_hash, status_code, response_body
        FROM ledger.idempotency_key WHERE client_id = ? AND key = ?
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setString(1, clientId);
      select.setString(2, key);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Stored(
                row.getLong("id"),
                row.getBytes("request_hash"),
                row.getInt("status_code"),
                row.getString("response_body")));
      }
    }
  }

  public void complete(Connection connection, long id, int statusCode, String responseBody)
      throws SQLException {
    String sql =
        """
        UPDATE ledger.idempotency_key SET status_code = ?, response_body = ? WHERE id = ?
        """;
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setInt(1, statusCode);
      update.setString(2, responseBody);
      update.setLong(3, id);
      update.executeUpdate();
    }
  }
}
