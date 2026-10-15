package tally.extbank;

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
import org.springframework.stereotype.Component;

@Component
public class PaymentStore {

  public Optional<ExtbankDto.BankPayment> find(Connection connection, String reference)
      throws SQLException {
    String sql =
        """
        SELECT reference, creditor_account, amount, currency, status, reject_reason,
          value_date, confirmations
        FROM extbank.payment WHERE reference = ?
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setString(1, reference);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(payment(row)) : Optional.empty();
      }
    }
  }

  public void insert(Connection connection, ExtbankDto.BankPayment payment, Instant receivedAt)
      throws SQLException {
    String sql =
        """
        INSERT INTO extbank.payment (reference, creditor_account, amount, currency, status,
          reject_reason, value_date, confirmations, received_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setString(1, payment.reference());
      insert.setString(2, payment.creditorAccount());
      insert.setLong(3, payment.amount());
      insert.setString(4, payment.currency());
      insert.setString(5, payment.status());
      insert.setString(6, payment.rejectReason());
      insert.setObject(7, payment.valueDate());
      insert.setInt(8, payment.confirmations());
      insert.setObject(9, time(receivedAt));
      insert.executeUpdate();
    }
  }

  public void confirm(
      Connection connection, String reference, LocalDate valueDate, int confirmations)
      throws SQLException {
    String sql =
        """
        UPDATE extbank.payment SET status = 'CONFIRMED', value_date = ?, confirmations = ?
        WHERE reference = ?
        """;
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setObject(1, valueDate);
      update.setInt(2, confirmations);
      update.setString(3, reference);
      update.executeUpdate();
    }
  }

  public List<ExtbankDto.BankPayment> confirmedOn(Connection connection, LocalDate date)
      throws SQLException {
    String sql =
        """
        SELECT reference, creditor_account, amount, currency, status, reject_reason,
          value_date, confirmations
        FROM extbank.payment WHERE status = 'CONFIRMED' AND value_date = ? ORDER BY reference
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, date);
      try (ResultSet rows = select.executeQuery()) {
        List<ExtbankDto.BankPayment> payments = new ArrayList<>();
        while (rows.next()) {
          payments.add(payment(rows));
        }
        return payments;
      }
    }
  }

  private static ExtbankDto.BankPayment payment(ResultSet row) throws SQLException {
    return new ExtbankDto.BankPayment(
        row.getString("reference"),
        row.getString("creditor_account"),
        row.getLong("amount"),
        row.getString("currency").trim(),
        row.getString("status"),
        row.getString("reject_reason"),
        row.getObject("value_date", LocalDate.class),
        row.getInt("confirmations"));
  }

  private static OffsetDateTime time(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
