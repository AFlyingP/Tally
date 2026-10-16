package tally.proof.fault;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tally.ledger.check.Violation;
import tally.proof.fault.LoadDriver.OpOutcome;

public record FaultResult(
    Scenario scenario,
    String strategy,
    int operations,
    int posted,
    int rejected,
    int unresolved,
    int duplicatePostings,
    int missingPostings,
    List<Violation> violations,
    long balanceSum,
    Integer duplicateScheduled,
    Integer missingEvents,
    Integer duplicateEvents,
    Map<String, Integer> paymentStates,
    Long createdOrLost,
    Map<String, Integer> monitor) {

  public static FaultResult collect(
      Scenario s, String strategy, FaultStack stack, LoadDriver driver) {
    Map<String, OpOutcome> outcomes = driver.outcomes();
    String pattern = driver.runId() + "-%";
    try (Connection connection = stack.jdbc();
        PreparedStatement duplicates =
            connection.prepareStatement(
                "SELECT description, count(*) FROM ledger.ledger_transaction"
                    + " WHERE description LIKE ? GROUP BY description HAVING count(*) > 1");
        PreparedStatement transactions =
            connection.prepareStatement(
                "SELECT description FROM ledger.ledger_transaction WHERE description LIKE ?");
        PreparedStatement balances =
            connection.prepareStatement(
                "SELECT coalesce(sum(balance), 0) FROM ledger.account WHERE id = ANY(?)")) {
      duplicates.setString(1, pattern);
      int duplicatePostings = 0;
      try (ResultSet rows = duplicates.executeQuery()) {
        while (rows.next()) {
          duplicatePostings++;
        }
      }
      transactions.setString(1, pattern);
      Set<String> descriptions = new HashSet<>();
      try (ResultSet rows = transactions.executeQuery()) {
        while (rows.next()) {
          descriptions.add(rows.getString(1));
        }
      }
      int missingPostings = 0;
      for (Map.Entry<String, OpOutcome> entry : outcomes.entrySet()) {
        boolean exists = descriptions.contains(entry.getKey());
        if ((entry.getValue() == OpOutcome.POSTED && !exists)
            || (entry.getValue() == OpOutcome.REJECTED && exists)) {
          missingPostings++;
        }
      }
      Array ids = connection.createArrayOf("uuid", driver.accounts().toArray(UUID[]::new));
      long balanceSum;
      try {
        balances.setArray(1, ids);
        try (ResultSet rows = balances.executeQuery()) {
          rows.next();
          balanceSum = rows.getLong(1);
        }
      } finally {
        ids.free();
      }
      return new FaultResult(
          s,
          strategy,
          outcomes.size(),
          Collections.frequency(outcomes.values(), OpOutcome.POSTED),
          Collections.frequency(outcomes.values(), OpOutcome.REJECTED),
          Collections.frequency(outcomes.values(), OpOutcome.UNRESOLVED),
          duplicatePostings,
          missingPostings,
          stack.checker(),
          balanceSum,
          null,
          null,
          null,
          null,
          null,
          null);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to read fault results", e);
    }
  }

  public void assertClean() {
    assertThat(unresolved).as("unresolved operations").isZero();
    assertThat(duplicatePostings).as("duplicate postings").isZero();
    assertThat(missingPostings).as("posting outcomes").isZero();
    assertThat(violations).as("ledger invariants").isEmpty();
    assertThat(balanceSum).as("customer balances").isEqualTo(5_000_000L);
    assertThat(duplicateScheduled == null || duplicateScheduled == 0)
        .as("duplicate scheduled transactions")
        .isTrue();
    assertThat(missingEvents == null || missingEvents == 0).as("missing events").isTrue();
    assertThat(createdOrLost == null || createdOrLost == 0).as("created or lost money").isTrue();
  }
}
