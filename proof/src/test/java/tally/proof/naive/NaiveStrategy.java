package tally.proof.naive;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import tally.ledger.posting.JdbcTx;
import tally.ledger.posting.PostingStrategy;
import tally.ledger.posting.RetryableConflict;
import tally.ledger.posting.TxWork;
import tally.ledger.store.AccountStore;

public class NaiveStrategy implements PostingStrategy {

  private final DataSource dataSource;
  private final Clock clock;
  private final AccountStore store;

  public NaiveStrategy(DataSource dataSource, Clock clock, AccountStore store) {
    this.dataSource = dataSource;
    this.clock = clock;
    this.store = store;
  }

  @Override
  public String name() {
    return "naive";
  }

  @Override
  public <T> T run(TxWork<T> work) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try {
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        T value = work.apply(new JdbcTx(connection, clock, store, false, false));
        connection.commit();
        return value;
      } catch (SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          e.addSuppressed(rollbackFailure);
        }
        if (e instanceof SQLException sql && "40P01".equals(sql.getSQLState())) {
          RetryableConflict conflict = new RetryableConflict(sql.getSQLState());
          conflict.initCause(sql);
          throw conflict;
        }
        throw e;
      }
    }
  }
}
