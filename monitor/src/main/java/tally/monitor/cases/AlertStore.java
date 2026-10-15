package tally.monitor.cases;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.monitor.rules.RuleEngine;

@Component
public class AlertStore {

  public OptionalLong insert(Connection connection, RuleEngine.AlertCandidate alert, Instant now)
      throws SQLException {
    String sql =
        """
        INSERT INTO monitor.alert (rule, account_id, trigger_key, alert_time, detail, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (rule, account_id, trigger_key) DO NOTHING
        RETURNING id
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setString(1, alert.rule().name());
      insert.setObject(2, alert.accountId());
      insert.setString(3, alert.triggerKey());
      insert.setObject(4, timestamp(alert.alertTime()));
      insert.setString(5, alert.detail());
      insert.setObject(6, timestamp(now));
      try (ResultSet row = insert.executeQuery()) {
        return row.next() ? OptionalLong.of(row.getLong("id")) : OptionalLong.empty();
      }
    }
  }

  public void setCase(Connection connection, long alertId, UUID caseId) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement("UPDATE monitor.alert SET case_id = ? WHERE id = ?")) {
      update.setObject(1, caseId);
      update.setLong(2, alertId);
      update.executeUpdate();
    }
  }

  private static java.time.OffsetDateTime timestamp(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
  }
}
