package tally.ledger.posting;

import java.sql.SQLException;

@FunctionalInterface
public interface TxWork<T> {

  T apply(LedgerTx tx) throws SQLException;
}
