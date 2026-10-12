package tally.ledger.check;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class CheckCommand {

  private final DataSource dataSource;
  private final InvariantChecker checker;

  public CheckCommand(DataSource dataSource, InvariantChecker checker) {
    this.dataSource = dataSource;
    this.checker = checker;
  }

  public int run(String[] flags, PrintStream out, PrintStream err) {
    if (flags.length > 0) {
      err.println("check failed: unknown flag " + flags[0]);
      return 2;
    }

    List<Violation> violations;
    try (Connection connection = dataSource.getConnection()) {
      violations = checker.check(connection);
    } catch (SQLException e) {
      err.println("check failed: " + e.getMessage());
      return 2;
    }

    for (Violation violation : violations) {
      out.println(violation.line());
    }
    if (violations.isEmpty()) {
      out.println("invariants ok: 0 violations");
      return 0;
    }
    out.println("invariants failed: %d violations".formatted(violations.size()));
    return 1;
  }
}
