package tally.auth;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import tally.auth.ClientStore.ClientRow;
import tally.platform.security.Role;

@Component
public class JdbcClientRepository implements RegisteredClientRepository {

  private final ClientStore store;
  private final DataSource dataSource;
  private final AuthProperties properties;

  public JdbcClientRepository(ClientStore store, DataSource dataSource, AuthProperties properties) {
    this.store = store;
    this.dataSource = dataSource;
    this.properties = properties;
  }

  @Override
  public void save(RegisteredClient registeredClient) {
    throw new UnsupportedOperationException("clients are written through ClientStore");
  }

  @Override
  public RegisteredClient findById(String id) {
    return find(id);
  }

  @Override
  public RegisteredClient findByClientId(String clientId) {
    return find(clientId);
  }

  private RegisteredClient find(String clientId) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      Optional<ClientRow> row = store.find(connection, clientId);
      if (row.isEmpty() || row.get().disabledAt() != null) {
        return null;
      }
      ClientRow client = row.get();
      Role role = Role.fromWireName(client.role());
      return RegisteredClient.withId(client.clientId())
          .clientId(client.clientId())
          .clientSecret(client.secretHash())
          .clientName(client.role())
          .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
          .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
          .scopes(scopes -> scopes.addAll(role.scopes()))
          .tokenSettings(
              TokenSettings.builder()
                  .accessTokenTimeToLive(Duration.ofSeconds(properties.tokenTtlSeconds()))
                  .build())
          .build();
    } catch (SQLException e) {
      throw new UncategorizedSQLException("find client", null, e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }
}
