package tally.ledger.posting;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import tally.ledger.config.LedgerProperties;
import tally.ledger.store.AccountStore;

@Component
public class SerializableStrategy implements PostingStrategy {

  private final DataSource dataSource;
  private final Clock clock;
  private final AccountStore store;
  private final long lockWaitMs;

  public SerializableStrategy(
      DataSource dataSource, Clock clock, AccountStore store, LedgerProperties properties) {
    this.dataSource = dataSource;
    this.clock = clock;
    this.store = store;
    this.lockWaitMs = properties.lockWaitMs();
  }

  @Override
  public String name() {
    return "serializable";
  }

  @Override
  public <T> T run(TxWork<T> work) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      try {
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        try (Statement statement = connection.createStatement()) {
          statement.execute("SET LOCAL statement_timeout = '10000ms'");
          statement.execute("SET LOCAL lock_timeout = '" + lockWaitMs + "ms'");
        }
        T value = work.apply(new JdbcTx(connection, clock, store, false, false));
        connection.commit();
        return value;
      } catch (SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          e.addSuppressed(rollbackFailure);
        }
        if (e instanceof SQLException sql
            && ("40001".equals(sql.getSQLState())
                || "40P01".equals(sql.getSQLState())
                || "55P03".equals(sql.getSQLState()))) {
          RetryableConflict conflict = new RetryableConflict(sql.getSQLState());
          conflict.initCause(sql);
          throw conflict;
        }
        throw e;
      }
    }
  }
}
