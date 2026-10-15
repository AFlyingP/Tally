package tally.ledger.check;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tally.ledger.outbox.OutboxStore;

@Component
public class CheckCommand {

  private final DataSource dataSource;
  private final InvariantChecker checker;

  @Value("${tally.ledger.kafka.bootstrap}")
  private String bootstrap;

  @Value("${tally.ledger.kafka.topic}")
  private String topic;

  public CheckCommand(DataSource dataSource, InvariantChecker checker) {
    this.dataSource = dataSource;
    this.checker = checker;
  }

  public int run(String[] flags, PrintStream out, PrintStream err) {
    boolean topicCheck = false;
    boolean requireDrained = false;
    for (String flag : flags) {
      switch (flag) {
        case "--topic-check" -> topicCheck = true;
        case "--require-drained" -> requireDrained = true;
        default -> {
          err.println("check failed: unknown flag " + flag);
          return 2;
        }
      }
    }
    if (requireDrained && !topicCheck) {
      err.println("check failed: --require-drained needs --topic-check");
      return 2;
    }

    List<Violation> violations;
    TopicCheck.Snapshot snapshot = null;
    try (Connection connection = dataSource.getConnection()) {
      violations = checker.check(connection);
      if (topicCheck) {
        // Establish the database snapshot before the relay can publish more events.
        new OutboxStore().publishedEventIds(connection);
        snapshot = TopicCheck.read(bootstrap, topic, Duration.ofSeconds(60));
        violations.addAll(checker.checkTopic(connection, snapshot, requireDrained));
        connection.commit();
      }
    } catch (SQLException | IllegalStateException e) {
      err.println("check failed: " + e.getMessage());
      return 2;
    }

    for (Violation violation : violations) {
      out.println(violation.line());
    }
    if (snapshot != null) {
      out.println(
          "topic events=%d duplicates=%d"
              .formatted(
                  snapshot.eventIds().size(), snapshot.records() - snapshot.eventIds().size()));
    }
    if (violations.isEmpty()) {
      out.println("invariants ok: 0 violations");
      return 0;
    }
    out.println("invariants failed: %d violations".formatted(violations.size()));
    return 1;
  }
}
