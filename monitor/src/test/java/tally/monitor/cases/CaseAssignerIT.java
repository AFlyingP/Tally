package tally.monitor.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tally.monitor.rules.Rule;
import tally.monitor.rules.RuleEngine;
import tally.monitor.testing.MonitorIT;
import tally.platform.id.UuidV7;

class CaseAssignerIT extends MonitorIT {

  private static final Instant START = Instant.parse("2026-03-10T00:00:00Z");

  @Test
  void firstAlertOpensCase() throws SQLException {
    UUID accountId = newAccount();
    long alertId;
    try (Connection connection = appJdbc()) {
      connection.setAutoCommit(false);
      alertId = assign(connection, accountId, Rule.R1, START);
      connection.commit();
    }

    List<CaseRow> rows = cases(accountId);
    assertThat(rows).hasSize(1);
    CaseRow row = rows.getFirst();
    assertThat(row.rule()).isEqualTo("R1");
    assertThat(row.status()).isEqualTo("OPEN");
    assertThat(row.alertCount()).isEqualTo(1);
    assertThat(caseIdFor(alertId)).isEqualTo(row.id());
    assertThat(events(row.id()))
        .containsExactly(new EventRow("OPENED", "monitor", "alert=" + alertId, null));
  }

  @Test
  void secondAlertWithinDayJoins() throws SQLException {
    UUID accountId = newAccount();
    long firstAlert;
    long secondAlert;
    try (Connection connection = appJdbc()) {
      connection.setAutoCommit(false);
      firstAlert = assign(connection, accountId, Rule.R1, START);
      secondAlert = assign(connection, accountId, Rule.R1, START.plusSeconds(86399));
      connection.commit();
    }

    List<CaseRow> rows = cases(accountId);
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().alertCount()).isEqualTo(2);
    assertThat(caseIdFor(firstAlert)).isEqualTo(rows.getFirst().id());
    assertThat(caseIdFor(secondAlert)).isEqualTo(rows.getFirst().id());
    assertThat(events(rows.getFirst().id()))
        .containsExactly(
            new EventRow("OPENED", "monitor", "alert=" + firstAlert, null),
            new EventRow("ALERT_ADDED", "monitor", "alert=" + secondAlert, null));
  }

  @Test
  void alertMoreThanDayLaterOpensNewCase() throws SQLException {
    UUID accountId = newAccount();
    long firstAlert;
    long secondAlert;
    try (Connection connection = appJdbc()) {
      connection.setAutoCommit(false);
      firstAlert = assign(connection, accountId, Rule.R1, START);
      secondAlert = assign(connection, accountId, Rule.R1, START.plusSeconds(86401));
      connection.commit();
    }

    List<CaseRow> rows = cases(accountId);
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(CaseRow::alertCount).containsExactly(1, 1);
    assertThat(caseIdFor(firstAlert)).isNotEqualTo(caseIdFor(secondAlert));
    assertThat(rows).extracting(CaseRow::status).containsOnly("OPEN");
  }

  @Test
  void alertForOtherRuleOpensOwnCase() throws SQLException {
    UUID accountId = newAccount();
    long firstAlert;
    long secondAlert;
    try (Connection connection = appJdbc()) {
      connection.setAutoCommit(false);
      firstAlert = assign(connection, accountId, Rule.R1, START);
      secondAlert = assign(connection, accountId, Rule.R2, START.plusSeconds(1));
      connection.commit();
    }

    assertThat(caseIdFor(firstAlert)).isNotEqualTo(caseIdFor(secondAlert));
    assertThat(cases(accountId)).extracting(CaseRow::rule).containsExactly("R1", "R2");
  }

  @Test
  void closedCaseIsNotJoined() throws SQLException {
    UUID accountId = newAccount();
    long firstAlert;
    long secondAlert;
    UUID closedCase;
    try (Connection connection = appJdbc()) {
      connection.setAutoCommit(false);
      firstAlert = assign(connection, accountId, Rule.R1, START);
      connection.commit();
    }
    closedCase = caseIdFor(firstAlert);
    try (Connection connection = superJdbc();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE monitor.case_file SET status = 'CLOSED_NO_ACTION' WHERE id = ?")) {
      update.setObject(1, closedCase);
      update.executeUpdate();
    }
    try (Connection connection = appJdbc()) {
      connection.setAutoCommit(false);
      secondAlert = assign(connection, accountId, Rule.R1, START.plusSeconds(1));
      connection.commit();
    }

    assertThat(caseIdFor(secondAlert)).isNotEqualTo(closedCase);
    assertThat(cases(accountId))
        .extracting(CaseRow::status)
        .containsExactly("CLOSED_NO_ACTION", "OPEN");
  }

  @Test
  void inReviewCaseIsJoined() throws SQLException {
    UUID accountId = newAccount();
    long firstAlert;
    long secondAlert;
    try (Connection connection = appJdbc()) {
      connection.setAutoCommit(false);
      firstAlert = assign(connection, accountId, Rule.R1, START);
      connection.commit();
    }
    UUID caseId = caseIdFor(firstAlert);
    try (Connection connection = superJdbc();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE monitor.case_file SET status = 'IN_REVIEW' WHERE id = ?")) {
      update.setObject(1, caseId);
      update.executeUpdate();
    }
    try (Connection connection = appJdbc()) {
      connection.setAutoCommit(false);
      secondAlert = assign(connection, accountId, Rule.R1, START.plusSeconds(1));
      connection.commit();
    }

    assertThat(caseIdFor(secondAlert)).isEqualTo(caseId);
    CaseRow row = cases(accountId).getFirst();
    assertThat(row.status()).isEqualTo("IN_REVIEW");
    assertThat(row.alertCount()).isEqualTo(2);
    assertThat(events(caseId))
        .containsExactly(
            new EventRow("OPENED", "monitor", "alert=" + firstAlert, null),
            new EventRow("ALERT_ADDED", "monitor", "alert=" + secondAlert, null));
  }

  @Test
  void caseEventsAreAppendOnly() throws SQLException {
    UUID accountId = newAccount();
    long alertId;
    try (Connection connection = appJdbc()) {
      connection.setAutoCommit(false);
      alertId = assign(connection, accountId, Rule.R1, START);
      connection.commit();
    }
    UUID caseId = caseIdFor(alertId);
    try (Connection connection = superJdbc();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE monitor.case_event SET detail = 'changed' WHERE case_id = ?")) {
      update.setObject(1, caseId);
      assertThatThrownBy(update::executeUpdate)
          .isInstanceOf(SQLException.class)
          .extracting(error -> ((SQLException) error).getSQLState())
          .isEqualTo("TL001");
    }
    assertThat(events(caseId))
        .containsExactly(new EventRow("OPENED", "monitor", "alert=" + alertId, null));
  }

  private long assign(Connection connection, UUID accountId, Rule rule, Instant alertTime)
      throws SQLException {
    AlertStore alerts = app().getBean(AlertStore.class);
    RuleEngine.AlertCandidate candidate =
        new RuleEngine.AlertCandidate(
            rule, accountId, UuidV7.next(clock()).toString(), alertTime, "test");
    long alertId = alerts.insert(connection, candidate, clock().instant()).orElseThrow();
    app().getBean(CaseAssigner.class).assign(connection, alertId, candidate);
    return alertId;
  }

  private UUID newAccount() {
    return UuidV7.next(clock());
  }

  private Clock clock() {
    return app().getBean(Clock.class);
  }

  private List<CaseRow> cases(UUID accountId) throws SQLException {
    String sql =
        """
        SELECT id, rule, status, alert_count FROM monitor.case_file
        WHERE account_id = ? ORDER BY opened_at, id
        """;
    try (Connection connection = appJdbc();
        PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, accountId);
      try (ResultSet rows = select.executeQuery()) {
        List<CaseRow> result = new ArrayList<>();
        while (rows.next()) {
          result.add(
              new CaseRow(
                  rows.getObject("id", UUID.class),
                  rows.getString("rule"),
                  rows.getString("status"),
                  rows.getInt("alert_count")));
        }
        return result;
      }
    }
  }

  private UUID caseIdFor(long alertId) throws SQLException {
    try (Connection connection = appJdbc();
        PreparedStatement select =
            connection.prepareStatement("SELECT case_id FROM monitor.alert WHERE id = ?")) {
      select.setLong(1, alertId);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getObject("case_id", UUID.class);
      }
    }
  }

  private List<EventRow> events(UUID caseId) throws SQLException {
    try (Connection connection = appJdbc();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT type, actor, detail, request_id FROM monitor.case_event"
                    + " WHERE case_id = ? ORDER BY id")) {
      select.setObject(1, caseId);
      try (ResultSet rows = select.executeQuery()) {
        List<EventRow> result = new ArrayList<>();
        while (rows.next()) {
          result.add(
              new EventRow(
                  rows.getString("type"),
                  rows.getString("actor"),
                  rows.getString("detail"),
                  rows.getString("request_id")));
        }
        return result;
      }
    }
  }

  private record CaseRow(UUID id, String rule, String status, int alertCount) {}

  private record EventRow(String type, String actor, String detail, String requestId) {}
}
