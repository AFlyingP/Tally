package tally.platform.web;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;

public class DbReadinessCheck implements ReadinessCheck {

  private final DataSource dataSource;

  public DbReadinessCheck(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public String name() {
    return "db";
  }

  @Override
  public boolean up() {
    // The pool's own connection timeout is longer than a readiness probe may take.
    try {
      return CompletableFuture.supplyAsync(this::selectOne).get(1000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (ExecutionException | TimeoutException e) {
      return false;
    }
  }

  private boolean selectOne() {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(1);
      statement.execute("SELECT 1");
      return true;
    } catch (SQLException e) {
      return false;
    }
  }
}
