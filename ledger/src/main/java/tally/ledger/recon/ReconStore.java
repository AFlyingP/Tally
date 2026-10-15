package tally.ledger.recon;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.ledger.core.BreakClassifier;
import tally.ledger.core.BreakType;

@Component
public class ReconStore {

  public record RunRow(
      UUID id,
      LocalDate businessDate,
      byte[] fileSha256,
      String status,
      Integer failureLine,
      String failureReason,
      UUID previousRunId,
      int statementLines,
      int ledgerPayments,
      int matched,
      int breakCount,
      Instant startedAt,
      Instant finishedAt) {}

  public Optional<RunRow> findByFile(Connection connection, LocalDate date, byte[] sha)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT * FROM ledger.recon_run WHERE business_date = ? AND file_sha256 = ?")) {
      select.setObject(1, date);
      select.setBytes(2, sha);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(run(row)) : Optional.empty();
      }
    }
  }

  public Optional<RunRow> latest(Connection connection, LocalDate date) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT * FROM ledger.recon_run WHERE business_date = ? ORDER BY started_at DESC LIMIT 1")) {
      select.setObject(1, date);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(run(row)) : Optional.empty();
      }
    }
  }

  public Optional<RunRow> find(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT * FROM ledger.recon_run WHERE id = ?")) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(run(row)) : Optional.empty();
      }
    }
  }

  public List<RunRow> byDate(Connection connection, LocalDate date) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT * FROM ledger.recon_run WHERE business_date = ? ORDER BY started_at")) {
      select.setObject(1, date);
      try (ResultSet rows = select.executeQuery()) {
        List<RunRow> result = new ArrayList<>();
        while (rows.next()) {
          result.add(run(rows));
        }
        return result;
      }
    }
  }

  public void insertRun(Connection connection, RunRow run) throws SQLException {
    String sql =
        """
        INSERT INTO ledger.recon_run (id, business_date, file_sha256, status, failure_line,
          failure_reason, previous_run_id, statement_lines, ledger_payments, matched,
          break_count, started_at, finished_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, run.id());
      insert.setObject(2, run.businessDate());
      insert.setBytes(3, run.fileSha256());
      insert.setString(4, run.status());
      insert.setObject(5, run.failureLine());
      insert.setString(6, run.failureReason());
      insert.setObject(7, run.previousRunId());
      insert.setInt(8, run.statementLines());
      insert.setInt(9, run.ledgerPayments());
      insert.setInt(10, run.matched());
      insert.setInt(11, run.breakCount());
      insert.setObject(12, run.startedAt().atOffset(ZoneOffset.UTC));
      insert.setObject(13, run.finishedAt().atOffset(ZoneOffset.UTC));
      insert.executeUpdate();
    }
  }

  public void insertBreaks(Connection connection, UUID runId, List<BreakClassifier.Break> breaks)
      throws SQLException {
    String sql =
        """
        INSERT INTO ledger.recon_break (run_id, type, reference, line_number, ledger_amount,
          ledger_currency, ledger_date, statement_amount, statement_currency, statement_date)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      for (BreakClassifier.Break item : breaks) {
        insert.setObject(1, runId);
        insert.setString(2, item.type().name());
        insert.setString(3, item.reference());
        insert.setObject(4, item.lineNumber());
        insert.setObject(5, item.ledgerAmount());
        insert.setString(6, item.ledgerCurrency());
        insert.setObject(7, item.ledgerDate());
        insert.setObject(8, item.statementAmount());
        insert.setString(9, item.statementCurrency());
        insert.setObject(10, item.statementDate());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  public List<BreakClassifier.Break> breaks(Connection connection, UUID runId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT * FROM ledger.recon_break WHERE run_id = ? ORDER BY reference")) {
      select.setObject(1, runId);
      try (ResultSet rows = select.executeQuery()) {
        List<BreakClassifier.Break> result = new ArrayList<>();
        while (rows.next()) {
          result.add(
              new BreakClassifier.Break(
                  BreakType.valueOf(rows.getString("type")),
                  rows.getString("reference"),
                  rows.getObject("line_number", Integer.class),
                  rows.getObject("ledger_amount", Long.class),
                  rows.getString("ledger_currency"),
                  rows.getObject("ledger_date", LocalDate.class),
                  rows.getObject("statement_amount", Long.class),
                  rows.getString("statement_currency"),
                  rows.getObject("statement_date", LocalDate.class)));
        }
        return result;
      }
    }
  }

  public List<BreakClassifier.LedgerPayment> ledgerPayments(
      Connection connection, LocalDate date, Collection<String> references) throws SQLException {
    String sql =
        """
        SELECT external_reference, amount, currency, value_date FROM ledger.external_payment
        WHERE state = 'SETTLED' AND (value_date = ? OR external_reference = ANY(?))
        """;
    Array array = connection.createArrayOf("text", references.toArray(String[]::new));
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, date);
      select.setArray(2, array);
      try (ResultSet rows = select.executeQuery()) {
        List<BreakClassifier.LedgerPayment> result = new ArrayList<>();
        while (rows.next()) {
          result.add(
              new BreakClassifier.LedgerPayment(
                  rows.getString("external_reference"), rows.getLong("amount"),
                  rows.getString("currency"), rows.getObject("value_date", LocalDate.class)));
        }
        return result;
      }
    } finally {
      array.free();
    }
  }

  private static RunRow run(ResultSet row) throws SQLException {
    return new RunRow(
        row.getObject("id", UUID.class),
        row.getObject("business_date", LocalDate.class),
        row.getBytes("file_sha256"),
        row.getString("status"),
        row.getObject("failure_line", Integer.class),
        row.getString("failure_reason"),
        row.getObject("previous_run_id", UUID.class),
        row.getInt("statement_lines"),
        row.getInt("ledger_payments"),
        row.getInt("matched"),
        row.getInt("break_count"),
        row.getObject("started_at", OffsetDateTime.class).toInstant(),
        row.getObject("finished_at", OffsetDateTime.class).toInstant());
  }
}
