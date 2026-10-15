package tally.monitor.cases;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.monitor.rules.Rule;
import tally.platform.id.UuidV7;

@Component
public class CaseStore {

  private static final String CASE_COLUMNS =
      "id, account_id, rule, status, assignee, alert_count, opened_at, updated_at, closed_at";

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

  public List<CaseRow> list(
      Connection connection,
      String status,
      String rule,
      UUID accountId,
      Instant afterOpenedAt,
      UUID afterId,
      int limit)
      throws SQLException {
    StringBuilder sql =
        new StringBuilder("SELECT " + CASE_COLUMNS + " FROM monitor.case_file WHERE 1=1");
    if (status != null) {
      sql.append(" AND status = ?");
    }
    if (rule != null) {
      sql.append(" AND rule = ?");
    }
    if (accountId != null) {
      sql.append(" AND account_id = ?");
    }
    if (afterOpenedAt != null) {
      sql.append(" AND (opened_at, id) > (?, ?)");
    }
    sql.append(" ORDER BY opened_at, id LIMIT ?");

    try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
      int parameter = 1;
      if (status != null) {
        select.setString(parameter++, status);
      }
      if (rule != null) {
        select.setString(parameter++, rule);
      }
      if (accountId != null) {
        select.setObject(parameter++, accountId);
      }
      if (afterOpenedAt != null) {
        select.setObject(parameter++, timestamp(afterOpenedAt));
        select.setObject(parameter++, afterId);
      }
      select.setInt(parameter, limit);
      try (ResultSet rows = select.executeQuery()) {
        List<CaseRow> result = new ArrayList<>();
        while (rows.next()) {
          result.add(caseRow(rows));
        }
        return result;
      }
    }
  }

  public Optional<CaseRow> find(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT " + CASE_COLUMNS + " FROM monitor.case_file WHERE id = ?")) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(caseRow(row)) : Optional.empty();
      }
    }
  }

  public Optional<CaseRow> lock(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT " + CASE_COLUMNS + " FROM monitor.case_file WHERE id = ? FOR UPDATE")) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(caseRow(row)) : Optional.empty();
      }
    }
  }

  public List<AlertRow> alerts(Connection connection, UUID caseId) throws SQLException {
    String sql =
        "SELECT id, rule, account_id, trigger_key, alert_time, detail, case_id, created_at"
            + " FROM monitor.alert WHERE case_id = ? ORDER BY id";
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, caseId);
      try (ResultSet rows = select.executeQuery()) {
        List<AlertRow> result = new ArrayList<>();
        while (rows.next()) {
          result.add(
              new AlertRow(
                  rows.getLong("id"),
                  rows.getString("rule"),
                  rows.getObject("account_id", UUID.class),
                  rows.getString("trigger_key"),
                  instant(rows, "alert_time"),
                  rows.getString("detail"),
                  rows.getObject("case_id", UUID.class),
                  instant(rows, "created_at")));
        }
        return result;
      }
    }
  }

  public List<EventRow> events(Connection connection, UUID caseId) throws SQLException {
    String sql =
        "SELECT id, case_id, type, actor, detail, request_id, occurred_at"
            + " FROM monitor.case_event WHERE case_id = ? ORDER BY id";
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, caseId);
      try (ResultSet rows = select.executeQuery()) {
        List<EventRow> result = new ArrayList<>();
        while (rows.next()) {
          result.add(
              new EventRow(
                  rows.getLong("id"),
                  rows.getObject("case_id", UUID.class),
                  rows.getString("type"),
                  rows.getString("actor"),
                  rows.getString("detail"),
                  rows.getString("request_id"),
                  instant(rows, "occurred_at")));
        }
        return result;
      }
    }
  }

  public void update(
      Connection connection, UUID id, String status, String assignee, Instant closedAt, Instant now)
      throws SQLException {
    String sql =
        "UPDATE monitor.case_file SET status = ?, assignee = ?, closed_at = ?, updated_at = ?"
            + " WHERE id = ?";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setString(1, status);
      update.setString(2, assignee);
      update.setObject(3, closedAt == null ? null : timestamp(closedAt));
      update.setObject(4, timestamp(now));
      update.setObject(5, id);
      update.executeUpdate();
    }
  }

  private static CaseRow caseRow(ResultSet row) throws SQLException {
    return new CaseRow(
        row.getObject("id", UUID.class),
        row.getObject("account_id", UUID.class),
        row.getString("rule"),
        row.getString("status"),
        row.getString("assignee"),
        row.getInt("alert_count"),
        instant(row, "opened_at"),
        instant(row, "updated_at"),
        nullableInstant(row, "closed_at"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, java.time.OffsetDateTime.class).toInstant();
  }

  private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
    java.time.OffsetDateTime value = row.getObject(column, java.time.OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  public record CaseRow(
      UUID id,
      UUID accountId,
      String rule,
      String status,
      String assignee,
      int alertCount,
      Instant openedAt,
      Instant updatedAt,
      Instant closedAt) {}

  public record AlertRow(
      long id,
      String rule,
      UUID accountId,
      String triggerKey,
      Instant alertTime,
      String detail,
      UUID caseId,
      Instant createdAt) {}

  public record EventRow(
      long id,
      UUID caseId,
      String type,
      String actor,
      String detail,
      String requestId,
      Instant occurredAt) {}

  private static java.time.OffsetDateTime timestamp(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }
}
