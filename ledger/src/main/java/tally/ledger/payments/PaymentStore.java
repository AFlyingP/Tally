package tally.ledger.payments;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.ledger.core.PaymentState;

@Component
public class PaymentStore {

  public record PaymentRow(
      UUID id,
      UUID sourceAccountId,
      String currency,
      long amount,
      String creditorAccount,
      String externalReference,
      PaymentState state,
      UUID holdId,
      UUID captureTransactionId,
      UUID resolutionTransactionId,
      LocalDate valueDate,
      int statusAttempts,
      Instant nextAttemptAt,
      String leaseOwner,
      Instant leaseUntil,
      String reason,
      long keyId,
      String clientId,
      String requestId,
      Instant createdAt,
      Instant updatedAt) {}

  public record EventRow(
      long id,
      UUID paymentId,
      String fromState,
      String toState,
      String detail,
      Instant occurredAt,
      UUID auditId) {}

  public record Changes(
      UUID holdId,
      UUID captureTransactionId,
      UUID resolutionTransactionId,
      LocalDate valueDate,
      Integer statusAttempts,
      Instant nextAttemptAt,
      String reason) {}

  public void insert(Connection connection, PaymentRow payment) throws SQLException {
    String sql =
        """
        INSERT INTO ledger.external_payment (id, source_account_id, currency, amount,
          creditor_account, external_reference, state, hold_id, capture_transaction_id,
          resolution_transaction_id, value_date, status_attempts, next_attempt_at, lease_owner,
          lease_until, reason, idempotency_key_id, client_id, request_id, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, payment.id());
      insert.setObject(2, payment.sourceAccountId());
      insert.setString(3, payment.currency());
      insert.setLong(4, payment.amount());
      insert.setString(5, payment.creditorAccount());
      insert.setString(6, payment.externalReference());
      insert.setString(7, payment.state().name());
      insert.setObject(8, payment.holdId());
      insert.setObject(9, payment.captureTransactionId());
      insert.setObject(10, payment.resolutionTransactionId());
      insert.setObject(11, payment.valueDate());
      insert.setInt(12, payment.statusAttempts());
      insert.setObject(13, time(payment.nextAttemptAt()));
      insert.setString(14, payment.leaseOwner());
      insert.setObject(15, payment.leaseUntil() == null ? null : time(payment.leaseUntil()));
      insert.setString(16, payment.reason());
      insert.setLong(17, payment.keyId());
      insert.setString(18, payment.clientId());
      insert.setString(19, payment.requestId());
      insert.setObject(20, time(payment.createdAt()));
      insert.setObject(21, time(payment.updatedAt()));
      insert.executeUpdate();
    }
  }

  public Optional<PaymentRow> find(Connection connection, UUID id) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT * FROM ledger.external_payment WHERE id = ?")) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(payment(row)) : Optional.empty();
      }
    }
  }

  public List<EventRow> events(Connection connection, UUID paymentId) throws SQLException {
    String sql = "SELECT * FROM ledger.payment_event WHERE payment_id = ? ORDER BY id";
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, paymentId);
      try (ResultSet rows = select.executeQuery()) {
        List<EventRow> events = new ArrayList<>();
        while (rows.next()) {
          events.add(
              new EventRow(
                  rows.getLong("id"),
                  rows.getObject("payment_id", UUID.class),
                  rows.getString("from_state"),
                  rows.getString("to_state"),
                  rows.getString("detail"),
                  instant(rows, "occurred_at"),
                  rows.getObject("audit_id", UUID.class)));
        }
        return events;
      }
    }
  }

  public List<PaymentRow> claim(
      Connection connection, String owner, Instant now, Instant leaseUntil, int limit)
      throws SQLException {
    String sql =
        """
        UPDATE ledger.external_payment SET lease_owner = ?, lease_until = ?
        WHERE id IN (SELECT id FROM ledger.external_payment
          WHERE state IN ('INITIATED','HELD','CAPTURED','SUBMITTING','UNKNOWN')
            AND next_attempt_at <= ? AND (lease_until IS NULL OR lease_until <= ?)
          ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
        RETURNING *
        """;
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setString(1, owner);
      update.setObject(2, time(leaseUntil));
      update.setObject(3, time(now));
      update.setObject(4, time(now));
      update.setInt(5, limit);
      try (ResultSet rows = update.executeQuery()) {
        List<PaymentRow> payments = new ArrayList<>();
        while (rows.next()) {
          payments.add(payment(rows));
        }
        return payments;
      }
    }
  }

  public void releaseLease(Connection connection, UUID id, String owner) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE ledger.external_payment SET lease_owner = NULL, lease_until = NULL"
                + " WHERE id = ? AND lease_owner = ?")) {
      update.setObject(1, id);
      update.setString(2, owner);
      update.executeUpdate();
    }
  }

  public boolean transition(
      Connection connection,
      UUID id,
      PaymentState from,
      PaymentState to,
      Changes changes,
      Instant now)
      throws SQLException {
    List<String> assignments = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    assignments.add("state = ?");
    values.add(to.name());
    add(assignments, values, "hold_id", changes.holdId());
    add(assignments, values, "capture_transaction_id", changes.captureTransactionId());
    add(assignments, values, "resolution_transaction_id", changes.resolutionTransactionId());
    add(assignments, values, "value_date", changes.valueDate());
    add(assignments, values, "status_attempts", changes.statusAttempts());
    add(
        assignments,
        values,
        "next_attempt_at",
        changes.nextAttemptAt() == null ? null : time(changes.nextAttemptAt()));
    add(assignments, values, "reason", changes.reason());
    assignments.add("updated_at = ?");
    values.add(time(now));
    values.add(id);
    values.add(from.name());
    String sql =
        "UPDATE ledger.external_payment SET "
            + String.join(", ", assignments)
            + " WHERE id = ? AND state = ?";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.size(); i++) {
        update.setObject(i + 1, values.get(i));
      }
      return update.executeUpdate() == 1;
    }
  }

  public boolean schedule(
      Connection connection, UUID id, int statusAttempts, Instant nextAttemptAt, Instant now)
      throws SQLException {
    String sql =
        "UPDATE ledger.external_payment SET status_attempts = ?, next_attempt_at = ?, updated_at = ?"
            + " WHERE id = ? AND state = 'UNKNOWN'";
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setInt(1, statusAttempts);
      update.setObject(2, time(nextAttemptAt));
      update.setObject(3, time(now));
      update.setObject(4, id);
      return update.executeUpdate() == 1;
    }
  }

  public void insertEvent(
      Connection connection,
      UUID paymentId,
      PaymentState from,
      PaymentState to,
      String detail,
      Instant now,
      UUID auditId)
      throws SQLException {
    String sql =
        "INSERT INTO ledger.payment_event (payment_id, from_state, to_state, detail, occurred_at, audit_id)"
            + " VALUES (?, ?, ?, ?, ?, ?)";
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, paymentId);
      insert.setString(2, from == null ? null : from.name());
      insert.setString(3, to.name());
      insert.setString(4, detail);
      insert.setObject(5, time(now));
      insert.setObject(6, auditId);
      insert.executeUpdate();
    }
  }

  public boolean hasOpenForAccount(Connection connection, UUID accountId) throws SQLException {
    String sql =
        "SELECT EXISTS (SELECT 1 FROM ledger.external_payment WHERE source_account_id = ?"
            + " AND state IN ('INITIATED','HELD','CAPTURED','SUBMITTING','UNKNOWN','MANUAL_REVIEW'))";
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, accountId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getBoolean(1);
      }
    }
  }

  private static void add(
      List<String> assignments, List<Object> values, String column, Object value) {
    if (value != null) {
      assignments.add(column + " = ?");
      values.add(value);
    }
  }

  private static PaymentRow payment(ResultSet row) throws SQLException {
    return new PaymentRow(
        row.getObject("id", UUID.class),
        row.getObject("source_account_id", UUID.class),
        row.getString("currency"),
        row.getLong("amount"),
        row.getString("creditor_account"),
        row.getString("external_reference"),
        PaymentState.valueOf(row.getString("state")),
        row.getObject("hold_id", UUID.class),
        row.getObject("capture_transaction_id", UUID.class),
        row.getObject("resolution_transaction_id", UUID.class),
        row.getObject("value_date", LocalDate.class),
        row.getInt("status_attempts"),
        instant(row, "next_attempt_at"),
        row.getString("lease_owner"),
        nullableInstant(row, "lease_until"),
        row.getString("reason"),
        row.getLong("idempotency_key_id"),
        row.getString("client_id"),
        row.getString("request_id"),
        instant(row, "created_at"),
        instant(row, "updated_at"));
  }

  private static OffsetDateTime time(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
