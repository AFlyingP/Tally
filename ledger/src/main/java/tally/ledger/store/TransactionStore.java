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
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.ledger.core.EntryLine;
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
}
