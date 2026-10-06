package tally.ledger.outbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** SQL for outbox rows. Every method runs on the caller's connection. */
@Component
public class OutboxStore {

  public void insert(
      Connection connection,
      UUID eventId,
      UUID transactionId,
      UUID keyAccountId,
      String requestId,
      String payload,
      Instant createdAt)
      throws SQLException {
    String sql =
        """
        INSERT INTO ledger.outbox
          (event_id, transaction_id, key_account_id, request_id, payload, created_at, published_at)
        VALUES (?, ?, ?, ?, ?, ?, NULL)
        """;
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, eventId);
      insert.setObject(2, transactionId);
      insert.setObject(3, keyAccountId);
      insert.setString(4, requestId);
      insert.setString(5, payload);
      insert.setObject(6, createdAt.atOffset(ZoneOffset.UTC));
      insert.executeUpdate();
    }
  }
}
