package tally.proof.harness;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** A lazily started database shared by in-process proof suites. */
public final class Postgres {

  private static final String PASSWORD = "test-password";
  private static Postgres shared;

  private final PostgreSQLContainer container;

  private Postgres() {
    Path init = Path.of(System.getProperty("proof.root", ".."), "deploy/postgres/init.sh");
    container =
        new PostgreSQLContainer("postgres:16.15-alpine")
            .withDatabaseName("tally")
            .withUsername("postgres")
            .withPassword(PASSWORD)
            .withEnv("LEDGER_OWNER_PASSWORD", PASSWORD)
            .withEnv("LEDGER_APP_PASSWORD", PASSWORD)
            .withEnv("MONITOR_DB_PASSWORD", PASSWORD)
            .withEnv("AUTH_DB_PASSWORD", PASSWORD)
            .withEnv("EXTBANK_DB_PASSWORD", PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forHostPath(init.toString(), 0755),
                "/docker-entrypoint-initdb.d/init.sh");
    container.start();
  }

  public static synchronized Postgres shared() {
    if (shared == null) {
      shared = new Postgres();
    }
    return shared;
  }

  public String jdbcUrl() {
    return container.getJdbcUrl();
  }

  public Connection app() throws SQLException {
    return DriverManager.getConnection(jdbcUrl(), "ledger_app", PASSWORD);
  }

  public Connection superuser() throws SQLException {
    return DriverManager.getConnection(jdbcUrl(), "postgres", PASSWORD);
  }

  public void reset() throws SQLException {
    try (Connection connection = superuser();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA ledger CASCADE");
      statement.execute("DROP SCHEMA monitor CASCADE");
      statement.execute("DROP SCHEMA auth CASCADE");
      statement.execute("DROP SCHEMA extbank CASCADE");
      statement.execute("CREATE SCHEMA ledger AUTHORIZATION ledger_owner");
      statement.execute("GRANT USAGE ON SCHEMA ledger TO ledger_app");
      statement.execute("CREATE SCHEMA monitor AUTHORIZATION monitor_app");
      statement.execute("CREATE SCHEMA auth AUTHORIZATION auth_app");
      statement.execute("CREATE SCHEMA extbank AUTHORIZATION extbank_app");
    }
  }
}
