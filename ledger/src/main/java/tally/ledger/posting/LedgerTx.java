package tally.ledger.posting;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.SortedSet;
import java.util.UUID;
import tally.ledger.core.AccountState;

public interface LedgerTx {

  Connection connection();

  /** Loads all IDs of an operation together; repeated loads return the first snapshot. */
  Map<UUID, AccountState> accounts(SortedSet<UUID> ids) throws SQLException;

  void write(AccountState before, long balance, long held) throws SQLException;
}
