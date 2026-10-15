package tally.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ClientStore {

  public boolean insert(
      Connection connection, String clientId, String secretHash, String role, Instant now)
      throws SQLException {
    String sql =
        "INSERT INTO auth.api_client (client_id, secret_hash, role, created_at) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT (client_id) DO NOTHING";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, clientId);
      statement.setString(2, secretHash);
      statement.setString(3, role);
      statement.setTimestamp(4, Timestamp.from(now));
      return statement.executeUpdate() == 1;
    }
  }

  public Optional<ClientRow> find(Connection connection, String clientId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT client_id, secret_hash, role, created_at, disabled_at "
                + "FROM auth.api_client WHERE client_id = ?")) {
      statement.setString(1, clientId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? Optional.of(readRow(result)) : Optional.empty();
      }
    }
  }

  public List<ClientRow> all(Connection connection) throws SQLException {
    List<ClientRow> rows = new ArrayList<>();
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT client_id, secret_hash, role, created_at, disabled_at "
                    + "FROM auth.api_client ORDER BY client_id");
        ResultSet result = statement.executeQuery()) {
      while (result.next()) {
        rows.add(readRow(result));
      }
    }
    return List.copyOf(rows);
  }

  public int disable(Connection connection, String clientId, Instant now) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE auth.api_client SET disabled_at = ? "
                + "WHERE client_id = ? AND disabled_at IS NULL")) {
      statement.setTimestamp(1, Timestamp.from(now));
      statement.setString(2, clientId);
      return statement.executeUpdate();
    }
  }

  public void event(
      Connection connection,
      String clientId,
      String type,
      String actor,
      String requestId,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO auth.client_event (client_id, type, actor, request_id, occurred_at) "
                + "VALUES (?, ?, ?, ?, ?)")) {
      statement.setString(1, clientId);
      statement.setString(2, type);
      statement.setString(3, actor);
      statement.setString(4, requestId);
      statement.setTimestamp(5, Timestamp.from(now));
      statement.executeUpdate();
    }
  }

  private static ClientRow readRow(ResultSet result) throws SQLException {
    Timestamp disabledAt = result.getTimestamp("disabled_at");
    return new ClientRow(
        result.getString("client_id"),
        result.getString("secret_hash"),
        result.getString("role"),
        result.getTimestamp("created_at").toInstant(),
        disabledAt == null ? null : disabledAt.toInstant());
  }

  public record ClientRow(
      String clientId, String secretHash, String role, Instant createdAt, Instant disabledAt) {}
}
