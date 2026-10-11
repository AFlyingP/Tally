package tally.ledger.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.ledger.api.TransactionDto;
import tally.ledger.core.EntryLine;
import tally.ledger.core.Side;
import tally.ledger.core.TransactionKind;

/** SQL for transactions and entries. Every method runs on the caller's connection. */
@Component
public class TransactionStore {

  public void insert(
      Connection connection,
      UUID id,
      TransactionKind kind,
      String currency,
      long amount,
      long keyId,
      UUID reversesId,
      String description,
      String clientId,
      String requestId,
      Instant postedAt)
      throws SQLException {
    String sql =
        """
        INSERT INTO ledger.ledger_transaction (id, kind, currency, amount, idempotency_key_id,
          reverses_id, description, client_id, request_id, posted_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, id);
      insert.setString(2, kind.name());
      insert.setString(3, currency);
      insert.setLong(4, amount);
      insert.setLong(5, keyId);
      insert.setObject(6, reversesId);
      insert.setString(7, description);
      insert.setString(8, clientId);
      insert.setString(9, requestId);
      insert.setObject(10, postedAt.atOffset(ZoneOffset.UTC));
      insert.executeUpdate();
    }
  }

  /** Inserts all lines in one statement and returns their IDs in line order. */
  public List<Long> insertEntries(
      Connection connection,
      UUID transactionId,
      String currency,
      List<EntryLine> lines,
      Instant postedAt)
      throws SQLException {
    String sql =
        """
        INSERT INTO ledger.entry (transaction_id, account_id, currency, side, amount, posted_at)
        VALUES %s RETURNING id
        """
            .formatted(String.join(", ", Collections.nCopies(lines.size(), "(?, ?, ?, ?, ?, ?)")));
    OffsetDateTime time = postedAt.atOffset(ZoneOffset.UTC);
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      int parameter = 1;
      for (EntryLine line : lines) {
        insert.setObject(parameter++, transactionId);
        insert.setObject(parameter++, line.accountId());
        insert.setString(parameter++, currency);
        insert.setString(parameter++, line.side().name());
        insert.setLong(parameter++, line.amount());
        insert.setObject(parameter++, time);
      }
      List<Long> ids = new ArrayList<>();
      try (ResultSet rows = insert.executeQuery()) {
        while (rows.next()) {
          ids.add(rows.getLong("id"));
        }
      }
      return ids;
    }
  }

  public Optional<TransactionDto.Transaction> find(Connection connection, UUID id)
      throws SQLException {
    String transactionSql =
        """
        SELECT id, kind, currency, amount, description, posted_at, reverses_id
        FROM ledger.ledger_transaction WHERE id = ?
        """;
    UUID transactionId;
    TransactionKind kind;
    String currency;
    long amount;
    String description;
    Instant postedAt;
    UUID reversesId;
    try (PreparedStatement select = connection.prepareStatement(transactionSql)) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        transactionId = row.getObject("id", UUID.class);
        kind = TransactionKind.valueOf(row.getString("kind"));
        currency = row.getString("currency");
        amount = row.getLong("amount");
        description = row.getString("description");
        postedAt = row.getObject("posted_at", OffsetDateTime.class).toInstant();
        reversesId = row.getObject("reverses_id", UUID.class);
      }
    }
    List<TransactionDto.Entry> entries = findEntries(connection, transactionId);
    UUID reversedById = findReversedBy(connection, transactionId);
    return Optional.of(
        new TransactionDto.Transaction(
            transactionId,
            kind,
            currency,
            amount,
            description,
            postedAt,
            reversesId,
            reversedById,
            entries));
  }

  private static List<TransactionDto.Entry> findEntries(Connection connection, UUID id)
      throws SQLException {
    String sql =
        """
        SELECT id, account_id, side, amount FROM ledger.entry
        WHERE transaction_id = ? ORDER BY id
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, id);
      try (ResultSet rows = select.executeQuery()) {
        List<TransactionDto.Entry> entries = new ArrayList<>();
        while (rows.next()) {
          entries.add(
              new TransactionDto.Entry(
                  rows.getLong("id"),
                  rows.getObject("account_id", UUID.class),
                  Side.valueOf(rows.getString("side")),
                  rows.getLong("amount")));
        }
        return entries;
      }
    }
  }

  private static UUID findReversedBy(Connection connection, UUID id) throws SQLException {
    String sql = "SELECT id FROM ledger.ledger_transaction WHERE reverses_id = ?";
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? row.getObject("id", UUID.class) : null;
      }
    }
  }
}
