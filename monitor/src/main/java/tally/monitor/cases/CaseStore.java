package tally.monitor.cases;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.monitor.rules.Rule;
import tally.platform.id.UuidV7;

@Component
public class CaseStore {

  private final Clock clock;

  public CaseStore(Clock clock) {
    this.clock = clock;
  }

  public Optional<UUID> findOpenFor(
      Connection connection, UUID accountId, Rule rule, Instant after, Instant before)
      throws SQLException {
    String sql =
        """
        SELECT id FROM monitor.case_file
        WHERE account_id = ? AND rule = ? AND status IN ('OPEN','IN_REVIEW')
          AND opened_at > ? AND opened_at < ?
        ORDER BY opened_at DESC, id DESC
        LIMIT 1 FOR UPDATE
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, accountId);
      select.setString(2, rule.name());
      select.setObject(3, timestamp(after));
      select.setObject(4, timestamp(before));
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(row.getObject("id", UUID.class)) : Optional.empty();
      }
    }
  }

  public UUID open(Connection connection, UUID accountId, Rule rule, Instant openedAt, Instant now)
      throws SQLException {
    UUID id = UuidV7.next(clock);
    String sql =
        """
        INSERT INTO monitor.case_file
          (id, account_id, rule, status, alert_count, opened_at, updated_at)
        VALUES (?, ?, ?, 'OPEN', 1, ?, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, id);
      insert.setObject(2, accountId);
      insert.setString(3, rule.name());
      insert.setObject(4, timestamp(openedAt));
      insert.setObject(5, timestamp(now));
      insert.executeUpdate();
    }
    return id;
  }

  public void addAlert(Connection connection, UUID caseId, Instant now) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE monitor.case_file SET alert_count = alert_count + 1, updated_at = ? WHERE id = ?")) {
      update.setObject(1, timestamp(now));
      update.setObject(2, caseId);
      update.executeUpdate();
    }
  }

  public void event(
      Connection connection,
      UUID caseId,
      String type,
      String actor,
      String detail,
      String requestId,
      Instant now)
      throws SQLException {
    String sql =
        """
        INSERT INTO monitor.case_event (case_id, type, actor, detail, request_id, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, caseId);
      insert.setString(2, type);
      insert.setString(3, actor);
      insert.setString(4, detail);
      insert.setString(5, requestId);
      insert.setObject(6, timestamp(now));
      insert.executeUpdate();
    }
  }

  private static java.time.OffsetDateTime timestamp(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }
}
