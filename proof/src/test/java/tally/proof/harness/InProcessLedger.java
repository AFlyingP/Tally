package tally.proof.harness;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import tally.ledger.LedgerApplication;
import tally.ledger.check.InvariantChecker;
import tally.ledger.check.Violation;
import tally.platform.testing.TestIssuer;

/** Runs the ledger API against a fresh schema in the shared database. */
public final class InProcessLedger {

  private final ConfigurableApplicationContext context;
  private final TestIssuer issuer;

  private InProcessLedger(ConfigurableApplicationContext context, TestIssuer issuer) {
    this.context = context;
    this.issuer = issuer;
  }

  public static InProcessLedger start(String strategy, Clock clock, Class<?>... extraConfig) {
    return start(strategy, clock, (InProcessExtbank) null, extraConfig);
  }

  public static InProcessLedger start(
      String strategy, Clock clock, InProcessExtbank extbankOrNull, Class<?>... extraConfig) {
    Postgres postgres = Postgres.shared();
    if (extbankOrNull == null) {
      try {
        postgres.reset();
      } catch (SQLException e) {
        throw new IllegalStateException("failed to reset database", e);
      }
    }
    TestIssuer issuer = TestIssuer.start();
    try {
      List<String> arguments =
          new ArrayList<>(
              List.of(
                  "--server.port=0",
                  "--spring.datasource.url=" + postgres.jdbcUrl(),
                  "--spring.datasource.password=test-password",
                  "--spring.flyway.password=test-password",
                  "--tally.ledger.strategy=" + strategy,
                  "--tally.ledger.roles=api",
                  "--tally.auth.jwks-uri=" + issuer.jwksUri(),
                  "--tally.auth.issuer=" + issuer.issuer()));
      if (extbankOrNull != null) {
        arguments.add("--tally.ledger.extbank.url=" + extbankOrNull.url());
        arguments.add("--tally.ledger.extbank.api-key=" + extbankOrNull.apiKey());
        arguments.add("--tally.ledger.extbank.timeout-ms=2000");
      }
      ConfigurableApplicationContext context =
          LedgerApplication.builder()
              .sources(extraConfig)
              .initializers(ctx -> ctx.getBeanFactory().registerSingleton("clock", clock))
              .run(arguments.toArray(String[]::new));
      return new InProcessLedger(context, issuer);
    } catch (RuntimeException e) {
      issuer.close();
      throw e;
    }
  }

  public String url() {
    return "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
  }

  public <T> T bean(Class<T> type) {
    return context.getBean(type);
  }

  public List<Violation> check() throws SQLException {
    try (Connection connection = Postgres.shared().app()) {
      return new InvariantChecker().check(connection);
    }
  }

  public TestIssuer issuer() {
    return issuer;
  }

  public void stop() {
    try {
      context.close();
    } finally {
      issuer.close();
    }
  }
}
