package tally.monitor.cases;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.monitor.rules.RuleEngine;
import tally.monitor.rules.RuleProperties;

@Component
public class CaseAssigner {

  private final CaseStore cases;
  private final AlertStore alerts;
  private final RuleProperties properties;
  private final Clock clock;

  public CaseAssigner(CaseStore cases, AlertStore alerts, RuleProperties properties, Clock clock) {
    this.cases = cases;
    this.alerts = alerts;
    this.properties = properties;
    this.clock = clock;
  }

  public void assign(Connection connection, long alertId, RuleEngine.AlertCandidate alert)
      throws SQLException {
    long joinSeconds = properties.caseJoinSeconds();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Optional<UUID> existing =
        cases.findOpenFor(
            connection,
            alert.accountId(),
            alert.rule(),
            alert.alertTime().minusSeconds(joinSeconds),
            alert.alertTime().plusSeconds(joinSeconds));
    if (existing.isPresent()) {
      UUID caseId = existing.orElseThrow();
      alerts.setCase(connection, alertId, caseId);
      cases.addAlert(connection, caseId, now);
      cases.event(connection, caseId, "ALERT_ADDED", "monitor", "alert=" + alertId, null, now);
      return;
    }

    UUID caseId = cases.open(connection, alert.accountId(), alert.rule(), alert.alertTime(), now);
    alerts.setCase(connection, alertId, caseId);
    cases.event(connection, caseId, "OPENED", "monitor", "alert=" + alertId, null, now);
  }
}
