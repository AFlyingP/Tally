package tally.ledger.check;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class InvariantChecker {

  private static final String I1 =
      """
      SELECT t.id, count(e.id) AS lines,
        coalesce(sum(e.amount) FILTER (WHERE e.side = 'DEBIT'), 0) AS debits,
        coalesce(sum(e.amount) FILTER (WHERE e.side = 'CREDIT'), 0) AS credits,
        count(e.id) FILTER (WHERE e.currency <> t.currency) AS foreign_count
      FROM ledger.ledger_transaction t
      LEFT JOIN ledger.entry e ON e.transaction_id = t.id
      GROUP BY t.id, t.amount, t.currency
      HAVING count(e.id) < 2
        OR coalesce(sum(e.amount) FILTER (WHERE e.side = 'DEBIT'), 0)
          <> coalesce(sum(e.amount) FILTER (WHERE e.side = 'CREDIT'), 0)
        OR coalesce(sum(e.amount) FILTER (WHERE e.side = 'DEBIT'), 0) <> t.amount
        OR count(e.id) FILTER (WHERE e.currency <> t.currency) > 0
      ORDER BY t.id
      """;

  private static final String I2 =
      """
      SELECT a.id,
        coalesce(sum(CASE WHEN e.side = a.normal_side THEN e.amount ELSE -e.amount END), 0)
          AS entries,
        a.held, a.min_balance
      FROM ledger.account a
      LEFT JOIN ledger.entry e ON e.account_id = a.id
      WHERE a.kind = 'CUSTOMER'
      GROUP BY a.id, a.normal_side, a.held, a.min_balance
      HAVING coalesce(sum(CASE WHEN e.side = a.normal_side THEN e.amount ELSE -e.amount END), 0)
          - a.held < a.min_balance
      ORDER BY a.id
      """;

  private static final String I3 =
      """
      WITH expected(table_name, trigger_name) AS (
        VALUES
          ('ledger_transaction', 'ledger_transaction_no_update'),
          ('ledger_transaction', 'ledger_transaction_no_truncate'),
          ('entry', 'entry_no_update'),
          ('entry', 'entry_no_truncate'),
          ('entry', 'entry_parent'),
          ('ledger_transaction', 'ledger_transaction_balanced')
      )
      SELECT 'trigger=' || expected.trigger_name AS subject, 'missing' AS detail
      FROM expected
      LEFT JOIN pg_catalog.pg_namespace n ON n.nspname = 'ledger'
      LEFT JOIN pg_catalog.pg_class c
        ON c.relname = expected.table_name AND c.relnamespace = n.oid
      LEFT JOIN pg_catalog.pg_trigger t
        ON t.tgrelid = c.oid AND t.tgname = expected.trigger_name
      WHERE c.oid IS NULL OR t.oid IS NULL OR t.tgenabled <> 'O'
      UNION ALL
      SELECT 'privilege=ledger.ledger_transaction:UPDATE', 'granted'
      WHERE pg_catalog.has_table_privilege(
        'ledger_app', 'ledger.ledger_transaction', 'UPDATE')
      UNION ALL
      SELECT 'privilege=ledger.ledger_transaction:DELETE', 'granted'
      WHERE pg_catalog.has_table_privilege(
        'ledger_app', 'ledger.ledger_transaction', 'DELETE')
      UNION ALL
      SELECT 'privilege=ledger.entry:UPDATE', 'granted'
      WHERE pg_catalog.has_table_privilege('ledger_app', 'ledger.entry', 'UPDATE')
      UNION ALL
      SELECT 'privilege=ledger.entry:DELETE', 'granted'
      WHERE pg_catalog.has_table_privilege('ledger_app', 'ledger.entry', 'DELETE')
      ORDER BY subject
      """;

  private static final String I4 =
      """
      SELECT a.id, a.balance,
        coalesce(sum(CASE WHEN e.side = a.normal_side THEN e.amount ELSE -e.amount END), 0)
          AS entries
      FROM ledger.account a
      LEFT JOIN ledger.entry e ON e.account_id = a.id
      GROUP BY a.id, a.balance, a.normal_side
      HAVING a.balance <>
        coalesce(sum(CASE WHEN e.side = a.normal_side THEN e.amount ELSE -e.amount END), 0)
      ORDER BY a.id
      """;

  private static final String I5 =
      """
      SELECT 'key=' || t.idempotency_key_id::text AS subject,
        'transactions=' || count(*)::text AS detail
      FROM ledger.ledger_transaction t
      GROUP BY t.idempotency_key_id
      HAVING count(*) > 1
      UNION ALL
      SELECT 'transaction=' || t.id::text, 'missing key'
      FROM ledger.ledger_transaction t
      LEFT JOIN ledger.idempotency_key k ON k.id = t.idempotency_key_id
      WHERE k.id IS NULL
      ORDER BY subject
      """;

  private static final String I6 =
      """
      SELECT a.id, 'held=' || a.held::text || ' active=' || coalesce(sum(h.amount), 0)::text AS detail
      FROM ledger.account a
      LEFT JOIN ledger.hold h ON h.account_id = a.id AND h.state = 'ACTIVE'
      GROUP BY a.id, a.held
      HAVING a.held <> coalesce(sum(h.amount), 0)
      UNION ALL
      SELECT a.id, 'available=' || (a.balance - a.held)::text || ' min=' || a.min_balance::text
      FROM ledger.account a
      WHERE a.kind = 'CUSTOMER' AND a.balance - a.held < a.min_balance
      ORDER BY id, detail
      """;

  private static final String I7 =
      """
      SELECT h.id, 'events=' || count(*)::text AS detail
      FROM ledger.hold h
      JOIN ledger.hold_event e ON e.hold_id = h.id AND e.type <> 'PLACED'
      GROUP BY h.id
      HAVING count(*) > 1
      UNION ALL
      SELECT h.id,
        'captured=' || h.captured_amount::text || ' transaction=' || t.amount::text
      FROM ledger.hold h
      JOIN ledger.ledger_transaction t ON t.id = h.capture_transaction_id
      WHERE h.state = 'CAPTURED'
        AND (h.captured_amount <> t.amount OR h.captured_amount > h.amount)
      UNION ALL
      SELECT h.id, 'state=' || h.state || ' event=' || coalesce(last_event.type, 'ACTIVE')
      FROM ledger.hold h
      LEFT JOIN LATERAL (
        SELECT e.type
        FROM ledger.hold_event e
        WHERE e.hold_id = h.id AND e.type <> 'PLACED'
        ORDER BY e.occurred_at DESC, e.id DESC
        LIMIT 1
      ) last_event ON true
      WHERE h.state <> coalesce(last_event.type, 'ACTIVE')
      ORDER BY id, detail
      """;

  private static final String I10 =
      """
      SELECT 'transaction=' || t.id::text AS subject, 'missing event' AS detail
      FROM ledger.ledger_transaction t
      LEFT JOIN ledger.outbox o ON o.transaction_id = t.id
      GROUP BY t.id
      HAVING count(o.id) <> 1
      UNION ALL
      SELECT 'outbox=' || o.id::text, 'missing transaction'
      FROM ledger.outbox o
      LEFT JOIN ledger.ledger_transaction t ON t.id = o.transaction_id
      WHERE t.id IS NULL
      ORDER BY subject
      """;

  private static final String A1 =
      """
      SELECT 'transaction=' || t.id::text AS subject,
        'audit_rows=' || count(a.id)::text AS detail
      FROM ledger.ledger_transaction t
      LEFT JOIN ledger.audit_log a ON a.transaction_id = t.id
      GROUP BY t.id
      HAVING count(a.id) <> 1
      UNION ALL
      SELECT 'audit=' || a.id::text, 'missing transaction'
      FROM ledger.audit_log a
      LEFT JOIN ledger.ledger_transaction t ON t.id = a.transaction_id
      WHERE a.transaction_id IS NOT NULL AND t.id IS NULL
      UNION ALL
      SELECT 'hold=' || e.hold_id::text, 'event=' || e.id::text || ' missing audit'
      FROM ledger.hold_event e
      LEFT JOIN ledger.audit_log a ON a.id = e.audit_id
      WHERE a.id IS NULL
      ORDER BY subject
      """;

  private static final String I8 =
      """
      WITH duplicate_transactions AS (
        SELECT transaction_id, count(*) AS total
        FROM ledger.schedule_occurrence
        WHERE transaction_id IS NOT NULL
        GROUP BY transaction_id
        HAVING count(*) > 1
      )
      SELECT 'schedule=' || schedule_id::text || ' date=' || occurrence_date::text AS subject,
        'transactions=' || count(*)::text AS detail
      FROM ledger.schedule_occurrence
      GROUP BY schedule_id, occurrence_date
      HAVING count(*) > 1
      UNION ALL
      SELECT 'occurrence=' || o.id::text, 'transactions=' || d.total::text
      FROM ledger.schedule_occurrence o
      JOIN duplicate_transactions d ON d.transaction_id = o.transaction_id
      UNION ALL
      SELECT 'occurrence=' || o.id::text, 'missing transaction'
      FROM ledger.schedule_occurrence o
      LEFT JOIN ledger.ledger_transaction t ON t.id = o.transaction_id
      WHERE o.state = 'POSTED' AND t.id IS NULL
      UNION ALL
      SELECT 'schedule=' || o.schedule_id::text || ' date=' || o.occurrence_date::text,
        'transactions=' || count(*)::text
      FROM ledger.schedule_occurrence o
      JOIN ledger.idempotency_key k
        ON k.client_id = 'job:scheduler'
        AND k.key = 'sched:' || o.schedule_id::text || ':' || o.occurrence_date::text
      JOIN ledger.ledger_transaction t ON t.idempotency_key_id = k.id
      GROUP BY o.schedule_id, o.occurrence_date
      HAVING count(*) > 1
      ORDER BY subject
      """;

  public List<Violation> check(Connection connection) throws SQLException {
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    connection.setReadOnly(true);
    connection.setAutoCommit(false);

    List<Violation> violations = new ArrayList<>();
    violations.addAll(
        read(
            connection,
            I1,
            row ->
                new Violation(
                    "I1",
                    "transaction=" + row.getObject("id"),
                    "lines=%d debits=%s credits=%s"
                        .formatted(
                            row.getLong("lines"),
                            row.getBigDecimal("debits").toPlainString(),
                            row.getBigDecimal("credits").toPlainString()))));
    violations.addAll(
        read(
            connection,
            I2,
            row ->
                new Violation(
                    "I2",
                    "account=" + row.getObject("id"),
                    "entries=%s held=%d min=%d"
                        .formatted(
                            row.getBigDecimal("entries").toPlainString(),
                            row.getLong("held"),
                            row.getLong("min_balance")))));
    violations.addAll(
        read(
            connection,
            I3,
            row -> new Violation("I3", row.getString("subject"), row.getString("detail"))));
    violations.addAll(
        read(
            connection,
            I4,
            row ->
                new Violation(
                    "I4",
                    "account=" + row.getObject("id"),
                    "stored=%d entries=%s"
                        .formatted(
                            row.getLong("balance"),
                            row.getBigDecimal("entries").toPlainString()))));
    violations.addAll(
        read(
            connection,
            I5,
            row -> new Violation("I5", row.getString("subject"), row.getString("detail"))));
    violations.addAll(
        read(
            connection,
            I6,
            row -> new Violation("I6", "account=" + row.getObject("id"), row.getString("detail"))));
    violations.addAll(
        read(
            connection,
            I7,
            row -> new Violation("I7", "hold=" + row.getObject("id"), row.getString("detail"))));
    violations.addAll(
        read(
            connection,
            I8,
            row -> new Violation("I8", row.getString("subject"), row.getString("detail"))));
    violations.addAll(
        read(
            connection,
            I10,
            row -> new Violation("I10", row.getString("subject"), row.getString("detail"))));
    violations.addAll(
        read(
            connection,
            A1,
            row -> new Violation("A1", row.getString("subject"), row.getString("detail"))));
    connection.commit();
    return violations;
  }

  private static List<Violation> read(Connection connection, String sql, RowMapper mapper)
      throws SQLException {
    try (PreparedStatement select = connection.prepareStatement(sql);
        ResultSet rows = select.executeQuery()) {
      List<Violation> violations = new ArrayList<>();
      while (rows.next()) {
        violations.add(mapper.map(rows));
      }
      return violations;
    }
  }

  @FunctionalInterface
  private interface RowMapper {
    Violation map(ResultSet row) throws SQLException;
  }
}
