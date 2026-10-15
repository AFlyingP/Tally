package tally.ledger.audit;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tally.platform.security.RequestDenied;

@Component
public class DenialAuditor {

  private static final Logger LOG = LoggerFactory.getLogger(DenialAuditor.class);

  private final DataSource dataSource;
  private final AuditWriter writer;

  public DenialAuditor(DataSource dataSource, AuditWriter writer) {
    this.dataSource = dataSource;
    this.writer = writer;
  }

  @EventListener
  void on(RequestDenied e) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        writer.record(
            connection,
            new AuditEntry(
                e.clientId(),
                e.role(),
                e.scopes() == null ? "" : e.scopes(),
                "request.denied",
                "request",
                e.method() + " " + e.path(),
                e.requestId(),
                "DENIED",
                e.code().name(),
                null));
        connection.commit();
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException exception) {
      LOG.error("failed to audit denied request", exception);
    }
  }
}
