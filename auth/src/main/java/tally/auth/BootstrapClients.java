package tally.auth;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tally.platform.security.Role;

/** The one client per role that is configured through the environment. */
@Component
public class BootstrapClients {

  private final AuthProperties properties;
  private final PasswordEncoder encoder;
  private final ClientStore store;
  private final DataSource dataSource;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public BootstrapClients(
      AuthProperties properties,
      PasswordEncoder encoder,
      ClientStore store,
      DataSource dataSource,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.properties = properties;
    this.encoder = encoder;
    this.store = store;
    this.dataSource = dataSource;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void bootstrap() {
    Instant now = clock.instant();
    transactions.executeWithoutResult(
        status -> {
          Connection connection = DataSourceUtils.getConnection(dataSource);
          try {
            for (Role role : Role.values()) {
              AuthProperties.Client client = properties.clients().get(role.wireName());
              if (store.insert(
                  connection, client.id(), encoder.encode(client.secret()), role.wireName(), now)) {
                store.event(connection, client.id(), "BOOTSTRAPPED", "auth", null, now);
              }
            }
          } catch (SQLException e) {
            throw new UncategorizedSQLException("bootstrap clients", null, e);
          } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
          }
        });
  }
}
