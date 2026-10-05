package tally.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import tally.platform.security.Role;

/** The one client per role that is configured through the environment. */
@Component
public class BootstrapClients {

  private final AuthProperties properties;
  private final PasswordEncoder encoder;

  public BootstrapClients(AuthProperties properties, PasswordEncoder encoder) {
    this.properties = properties;
    this.encoder = encoder;
  }

  public List<RegisteredClient> registeredClients() {
    TokenSettings tokens =
        TokenSettings.builder()
            .accessTokenTimeToLive(Duration.ofSeconds(properties.tokenTtlSeconds()))
            .build();
    List<RegisteredClient> clients = new ArrayList<>();
    for (Role role : Role.values()) {
      AuthProperties.Client client = properties.clients().get(role.wireName());
      clients.add(
          RegisteredClient.withId(client.id())
              .clientId(client.id())
              .clientSecret(encoder.encode(client.secret()))
              .clientName(role.wireName())
              .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
              .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
              .scopes(scopes -> scopes.addAll(role.scopes()))
              .tokenSettings(tokens)
              .build());
    }
    return clients;
  }
}
