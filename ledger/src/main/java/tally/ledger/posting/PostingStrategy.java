package tally.ledger.posting;

import java.sql.SQLException;

/** Runs work once in a database transaction with the strategy's isolation and locking. */
public interface PostingStrategy {

  String name();

  <T> T run(TxWork<T> work) throws SQLException;
}
