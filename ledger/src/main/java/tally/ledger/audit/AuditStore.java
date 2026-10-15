package tally.ledger.audit;

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
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tally.ledger.api.AuditDto;

@Component
public class AuditStore {

  public List<AuditDto.AuditRow> query(
      Connection connection,
      String resourceType,
      String resourceId,
      Instant from,
      Instant to,
      Instant afterTime,
      UUID afterId,
      int limit)
      throws SQLException {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT id, occurred_at, actor, actor_role, scopes, action, resource_type,
              resource_id, request_id, outcome, reason, transaction_id
            FROM ledger.audit_log WHERE true
            """);
    if (resourceType != null) {
      sql.append(" AND resource_type = ? AND resource_id = ?");
    }
    if (from != null) {
      sql.append(" AND occurred_at >= ?");
    }
    if (to != null) {
      sql.append(" AND occurred_at < ?");
    }
    if (afterTime != null) {
      sql.append(" AND (occurred_at, id) > (?, ?)");
    }
    sql.append(" ORDER BY occurred_at, id LIMIT ?");
    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      int index = 1;
      if (resourceType != null) {
        statement.setString(index++, resourceType);
        statement.setString(index++, resourceId);
      }
      if (from != null) {
        statement.setObject(index++, from.atOffset(ZoneOffset.UTC));
      }
      if (to != null) {
        statement.setObject(index++, to.atOffset(ZoneOffset.UTC));
      }
      if (afterTime != null) {
        statement.setObject(index++, afterTime.atOffset(ZoneOffset.UTC));
        statement.setObject(index++, afterId);
      }
      statement.setInt(index, limit);
      try (ResultSet result = statement.executeQuery()) {
        List<AuditDto.AuditRow> rows = new ArrayList<>();
        while (result.next()) {
          rows.add(
              new AuditDto.AuditRow(
                  result.getObject("id", UUID.class),
                  result.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                  result.getString("actor"),
                  result.getString("actor_role"),
                  result.getString("scopes"),
                  result.getString("action"),
                  result.getString("resource_type"),
                  result.getString("resource_id"),
                  result.getString("request_id"),
                  result.getString("outcome"),
                  result.getString("reason"),
                  result.getObject("transaction_id", UUID.class)));
        }
        return rows;
      }
    }
  }

  public List<UUID> takePending(Connection connection, int limit) throws SQLException {
    String sql =
        """
        DELETE FROM ledger.audit_pending WHERE audit_id IN
          (SELECT audit_id FROM ledger.audit_pending ORDER BY audit_id LIMIT ?)
        RETURNING audit_id
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, limit);
      try (ResultSet result = statement.executeQuery()) {
        List<UUID> ids = new ArrayList<>();
        while (result.next()) {
          ids.add(result.getObject(1, UUID.class));
        }
        return ids;
      }
    }
  }

  public List<HashRow> rowHashes(Connection connection, List<UUID> ids) throws SQLException {
    var array = connection.createArrayOf("uuid", ids.toArray(UUID[]::new));
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id, row_hash FROM ledger.audit_log WHERE id = ANY(?) ORDER BY occurred_at, id")) {
      statement.setArray(1, array);
      try (ResultSet result = statement.executeQuery()) {
        List<HashRow> rows = new ArrayList<>();
        while (result.next()) {
          rows.add(new HashRow(result.getObject(1, UUID.class), result.getBytes(2)));
        }
        return rows;
      }
    } finally {
      array.free();
    }
  }

  public Optional<ChainHead> head(Connection connection) throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT seq, chain_hash FROM ledger.audit_chain ORDER BY seq DESC LIMIT 1");
        ResultSet result = statement.executeQuery()) {
      return result.next()
          ? Optional.of(new ChainHead(result.getLong(1), result.getBytes(2)))
          : Optional.empty();
    }
  }

  public void appendChain(
      Connection connection, long seq, UUID auditId, byte[] prevHash, byte[] chainHash, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
        INSERT INTO ledger.audit_chain (seq, audit_id, prev_hash, chain_hash, sealed_at)
        VALUES (?, ?, ?, ?, ?)
        """)) {
      statement.setLong(1, seq);
      statement.setObject(2, auditId);
      statement.setBytes(3, prevHash);
      statement.setBytes(4, chainHash);
      statement.setObject(5, now.atOffset(ZoneOffset.UTC));
      statement.executeUpdate();
    }
  }

  public long pendingCount(Connection connection) throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM ledger.audit_pending");
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getLong(1);
    }
  }

  /** Streams links on a connection with auto-commit disabled so JDBC can fetch in batches. */
  public void walkChain(Connection connection, Consumer<ChainLink> visitor) throws SQLException {
    String sql =
        """
        SELECT c.seq, c.audit_id, c.prev_hash, c.chain_hash, a.*
        FROM ledger.audit_chain c LEFT JOIN ledger.audit_log a ON a.id = c.audit_id
        ORDER BY c.seq
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setFetchSize(1000);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          UUID id = result.getObject("id", UUID.class);
          AuditRowData row =
              id == null
                  ? null
                  : new AuditRowData(
                      id,
                      result.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                      result.getString("actor"),
                      result.getString("actor_role"),
                      result.getString("scopes"),
                      result.getString("action"),
                      result.getString("resource_type"),
                      result.getString("resource_id"),
                      result.getString("request_id"),
                      result.getString("outcome"),
                      result.getString("reason"),
                      result.getObject("transaction_id", UUID.class),
                      result.getBytes("row_hash"));
          visitor.accept(
              new ChainLink(
                  result.getLong("seq"),
                  result.getObject("audit_id", UUID.class),
                  result.getBytes("prev_hash"),
                  result.getBytes("chain_hash"),
                  row));
        }
      }
    }
  }

  public record HashRow(UUID id, byte[] rowHash) {}

  public record ChainHead(long seq, byte[] chainHash) {}

  public record ChainLink(
      long seq, UUID auditId, byte[] prevHash, byte[] chainHash, AuditRowData row) {}

  public record AuditRowData(
      UUID id,
      Instant occurredAt,
      String actor,
      String actorRole,
      String scopes,
      String action,
      String resourceType,
      String resourceId,
      String requestId,
      String outcome,
      String reason,
      UUID transactionId,
      byte[] rowHash) {}
}
