package tally.ledger.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A session lock owned by one job; not thread-safe. */
public final class AdvisoryLock {

  private static final Logger log = LoggerFactory.getLogger(AdvisoryLock.class);

  private final DataSource dataSource;
  private final long key;
  private Connection connection;
  private boolean acquired;

  public AdvisoryLock(DataSource dataSource, long key) {
    this.dataSource = dataSource;
    this.key = key;
  }

  public boolean tryAcquire() {
    try {
      if (connection != null) {
        try (Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(1);
          try (ResultSet result = statement.executeQuery("SELECT 1")) {
            return result.next();
          }
        }
      }

      connection = dataSource.getConnection();
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
        statement.setLong(1, key);
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          acquired = result.getBoolean(1);
        }
      }
      if (!acquired) {
        release();
      }
      return acquired;
    } catch (SQLException exception) {
      log.warn("advisory lock failed: {}", exception.getMessage());
      release();
      return false;
    }
  }

  public void release() {
    if (connection != null) {
      Connection held = connection;
      connection = null;
      boolean unlock = acquired;
      acquired = false;
      try {
        if (unlock) {
          try (PreparedStatement statement =
              held.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, key);
            statement.execute();
          }
        }
      } catch (SQLException exception) {
        log.warn("advisory unlock failed: {}", exception.getMessage());
      } finally {
        try {
          held.close();
        } catch (SQLException exception) {
          log.warn("advisory lock close failed: {}", exception.getMessage());
        }
      }
    }
  }
}
