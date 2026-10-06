package tally.ledger.posting;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import tally.ledger.core.AccountState;
import tally.ledger.store.AccountStore;

public class JdbcTx implements LedgerTx {

  private final Connection connection;
  private final Clock clock;
  private final AccountStore store;
  private final boolean lockRows;
  private final boolean checkVersion;
  private final Map<UUID, AccountState> cache = new HashMap<>();

  public JdbcTx(
      Connection connection,
      Clock clock,
      AccountStore store,
      boolean lockRows,
      boolean checkVersion) {
    this.connection = connection;
    this.clock = clock;
    this.store = store;
    this.lockRows = lockRows;
    this.checkVersion = checkVersion;
  }

  @Override
  public Connection connection() {
    return connection;
  }

  @Override
  public Map<UUID, AccountState> accounts(SortedSet<UUID> ids) throws SQLException {
    SortedSet<UUID> missing = new TreeSet<>(ids);
    missing.removeAll(cache.keySet());
    if (!missing.isEmpty()) {
      cache.putAll(store.load(connection, missing, lockRows));
    }
    Map<UUID, AccountState> accounts = new LinkedHashMap<>();
    for (UUID id : ids) {
      AccountState account = cache.get(id);
      if (account != null) {
        accounts.put(id, account);
      }
    }
    return accounts;
  }

  @Override
  public void write(AccountState before, long balance, long held) throws SQLException {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    int updated =
        store.update(
            connection, before.id(), balance, held, now, checkVersion ? before.version() : null);
    if (updated == 0) {
      if (checkVersion) {
        throw new RetryableConflict("version");
      }
      throw new IllegalStateException("account update affected no rows");
    }
  }
}
