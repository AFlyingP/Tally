package tally.ledger.audit;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class VerifyCommand {

  private final DataSource dataSource;
  private final AuditVerifier verifier;

  public VerifyCommand(DataSource dataSource, AuditVerifier verifier) {
    this.dataSource = dataSource;
    this.verifier = verifier;
  }

  public int run(String[] flags, PrintStream out, PrintStream err) {
    boolean requireSealed = false;
    for (String flag : flags) {
      if (!flag.equals("--require-sealed")) {
        err.println("verify failed: unknown flag " + flag);
        return 2;
      }
      requireSealed = true;
    }
    AuditVerifier.Report report;
    try (Connection connection = dataSource.getConnection()) {
      report = verifier.verify(connection);
    } catch (SQLException exception) {
      err.println("verify failed: " + exception.getMessage());
      return 2;
    }
    int result;
    if (report.brokenSeq() != null) {
      out.println(
          "audit chain broken at seq %d: %s".formatted(report.brokenSeq(), report.brokenReason()));
      result = 1;
    } else if (requireSealed && report.pendingRows() > 0) {
      out.println("audit chain incomplete: %d rows pending".formatted(report.pendingRows()));
      result = 1;
    } else {
      out.println("audit chain ok: %d rows".formatted(report.sealedRows()));
      result = 0;
    }
    if (!requireSealed && report.pendingRows() > 0) {
      out.println("pending: " + report.pendingRows());
    }
    return result;
  }
}
