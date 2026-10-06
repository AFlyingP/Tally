package tally.ledger.store;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.ledger.core.AccountKind;
import tally.ledger.core.AccountState;
import tally.ledger.core.AccountStatus;
import tally.ledger.core.Side;
import tally.platform.id.UuidV7;

/** SQL for the account table. Every method runs on the caller's connection. */
@Component
public class AccountStore {

  private static final String INSERT =
      """
      INSERT INTO ledger.account (id, kind, currency, normal_side, min_balance, status,
        balance, held, version, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, 'OPEN', 0, 0, 0, ?, ?)
      """;

  private final Clock clock;

  public AccountStore(Clock clock) {
    this.clock = clock;
  }

  public record AccountRow(
      UUID id,
      AccountKind kind,
      String currency,
      Side normalSide,
      Long minBalance,
      AccountStatus status,
      long balance,
      long held,
      long version,
      Instant createdAt) {}

  public void insertCustomer(
      Connection connection,
      UUID id,
      String currency,
      Side normalSide,
      long minBalance,
      Instant now)
      throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
      bind(insert, id, AccountKind.CUSTOMER, currency, normalSide, minBalance, now);
      insert.executeUpdate();
    }
  }

  /** Creates the settlement and clearing accounts of a currency unless they exist already. */
  public void ensureInternal(Connection connection, String currency, Instant now)
      throws SQLException {
    String sql = INSERT + "ON CONFLICT (kind, currency) WHERE kind <> 'CUSTOMER' DO NOTHING";
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      bind(insert, UuidV7.next(clock), AccountKind.SETTLEMENT, currency, Side.DEBIT, null, now);
      insert.executeUpdate();
      bind(insert, UuidV7.next(clock), AccountKind.CLEARING, currency, Side.CREDIT, null, now);
      insert.executeUpdate();
    }
  }

  public Optional<AccountRow> find(Connection connection, UUID id) throws SQLException {
    String sql =
        """
        SELECT id, kind, currency, normal_side, min_balance, status, balance, held, version,
          created_at
        FROM ledger.account WHERE id = ?
        """;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new AccountRow(
                row.getObject("id", UUID.class),
                AccountKind.valueOf(row.getString("kind")),
                row.getString("currency"),
                Side.valueOf(row.getString("normal_side")),
                row.getObject("min_balance", Long.class),
                AccountStatus.valueOf(row.getString("status")),
                row.getLong("balance"),
                row.getLong("held"),
                row.getLong("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant()));
      }
    }
  }

  public Map<UUID, AccountState> load(
      Connection connection, Collection<UUID> ids, boolean forUpdate) throws SQLException {
    String sql =
        """
        SELECT id, kind, currency, normal_side, min_balance, status, balance, held, version
        FROM ledger.account WHERE id = ANY(?) ORDER BY id
        """
            .stripTrailing();
    if (forUpdate) {
      sql += " FOR UPDATE";
    }
    Array accountIds = connection.createArrayOf("uuid", ids.toArray(UUID[]::new));
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setArray(1, accountIds);
      Map<UUID, AccountState> accounts = new LinkedHashMap<>();
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          AccountState account =
              new AccountState(
                  rows.getObject("id", UUID.class),
                  AccountKind.valueOf(rows.getString("kind")),
                  rows.getString("currency"),
                  Side.valueOf(rows.getString("normal_side")),
                  rows.getObject("min_balance", Long.class),
                  AccountStatus.valueOf(rows.getString("status")),
                  rows.getLong("balance"),
                  rows.getLong("held"),
                  rows.getLong("version"));
          accounts.put(account.id(), account);
        }
      }
      return accounts;
    } finally {
      accountIds.free();
    }
  }

  public int update(
      Connection connection, UUID id, long balance, long held, Instant now, Long expectedVersion)
      throws SQLException {
    String sql =
        """
        UPDATE ledger.account SET balance = ?, held = ?, version = version + 1, updated_at = ?
        WHERE id = ?
        """
            .stripTrailing();
    if (expectedVersion != null) {
      sql += " AND version = ?";
    }
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setLong(1, balance);
      update.setLong(2, held);
      update.setObject(3, now.atOffset(ZoneOffset.UTC));
      update.setObject(4, id);
      if (expectedVersion != null) {
        update.setLong(5, expectedVersion);
      }
      return update.executeUpdate();
    }
  }

  private static void bind(
      PreparedStatement insert,
      UUID id,
      AccountKind kind,
      String currency,
      Side normalSide,
      Long minBalance,
      Instant now)
      throws SQLException {
    OffsetDateTime time = now.atOffset(ZoneOffset.UTC);
    insert.setObject(1, id);
    insert.setString(2, kind.name());
    insert.setString(3, currency);
    insert.setString(4, normalSide.name());
    insert.setObject(5, minBalance);
    insert.setObject(6, time);
    insert.setObject(7, time);
  }
}
