package tally.ledger.store;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.ledger.api.AccountDto;
import tally.ledger.core.Side;

/** SQL for account entries. Every method runs on the caller's connection. */
@Component
public class EntryStore {

  public List<AccountDto.Entry> page(Connection connection, UUID accountId, long afterId, int limit)
      throws SQLException {
    String sql =
        """
        SELECT id, transaction_id, side, amount, posted_at FROM ledger.entry
        WHERE account_id = ? AND id > ? ORDER BY id LIMIT ?
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, accountId);
      select.setLong(2, afterId);
      select.setInt(3, limit);
      try (ResultSet rows = select.executeQuery()) {
        List<AccountDto.Entry> entries = new ArrayList<>();
        while (rows.next()) {
          entries.add(
              new AccountDto.Entry(
                  rows.getLong("id"),
                  rows.getObject("transaction_id", UUID.class),
                  Side.valueOf(rows.getString("side")),
                  rows.getLong("amount"),
                  rows.getObject("posted_at", OffsetDateTime.class).toInstant()));
        }
        return entries;
      }
    }
  }

  public long balanceAsOf(Connection connection, UUID accountId, Instant asOf) throws SQLException {
    String sql =
        """
        SELECT coalesce(sum(CASE WHEN e.side = a.normal_side THEN e.amount ELSE -e.amount END), 0)
        FROM ledger.entry e JOIN ledger.account a ON a.id = e.account_id
        WHERE e.account_id = ? AND e.posted_at <= ?
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, accountId);
      select.setObject(2, asOf.atOffset(ZoneOffset.UTC));
      try (ResultSet row = select.executeQuery()) {
        row.next();
        BigDecimal balance = row.getBigDecimal(1);
        return balance.longValueExact();
      }
    }
  }
}
