package tally.ledger.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.ledger.core.AuditHash;
import tally.platform.id.UuidV7;

@Component
public class AuditWriter {

  private static final DateTimeFormatter HASHED_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

  private final Clock clock;

  public AuditWriter(Clock clock) {
    this.clock = clock;
  }

  /** Inserts the row on the caller's connection, so it commits or rolls back with the change. */
  public UUID record(Connection connection, AuditEntry entry) throws SQLException {
    UUID id = UuidV7.next(clock);
    Instant occurredAt = clock.instant();
    UUID transactionId = entry.transactionId();
    byte[] rowHash =
        AuditHash.row(
            id.toString(),
            HASHED_TIME.format(occurredAt),
            entry.actor(),
            entry.actorRole(),
            entry.scopes(),
            entry.action(),
            entry.resourceType(),
            entry.resourceId(),
            entry.requestId(),
            entry.outcome(),
            entry.reason(),
            transactionId == null ? null : transactionId.toString());
    String sql =
        """
        INSERT INTO ledger.audit_log (id, occurred_at, actor, actor_role, scopes, action,
          resource_type, resource_id, request_id, outcome, reason, transaction_id, row_hash)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, id);
      insert.setObject(2, occurredAt.atOffset(ZoneOffset.UTC));
      insert.setString(3, entry.actor());
      insert.setString(4, entry.actorRole());
      insert.setString(5, entry.scopes());
      insert.setString(6, entry.action());
      insert.setString(7, entry.resourceType());
      insert.setString(8, entry.resourceId());
      insert.setString(9, entry.requestId());
      insert.setString(10, entry.outcome());
      insert.setString(11, entry.reason());
      insert.setObject(12, transactionId);
      insert.setBytes(13, rowHash);
      insert.executeUpdate();
    }
    return id;
  }
}
