package tally.auth;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import tally.platform.security.Role;

/** The tally.auth.* settings, checked once at startup. */
@ConfigurationProperties("tally.auth")
public record AuthProperties(
    String issuer, long tokenTtlSeconds, String keyPath, Map<String, Client> clients) {

  public record Client(String id, String secret) {}

  public AuthProperties {
    if (issuer == null || issuer.isBlank()) {
      throw invalid("tally.auth.issuer", "must not be empty");
    }
    if (tokenTtlSeconds < 60 || tokenTtlSeconds > 3600) {
      throw invalid("tally.auth.token-ttl-seconds", "must be between 60 and 3600");
    }
    if (keyPath == null || keyPath.isBlank()) {
      throw invalid("tally.auth.key-path", "must not be empty");
    }
    for (Role role : Role.values()) {
      String key = "tally.auth.clients." + role.wireName();
      Client client = clients == null ? null : clients.get(role.wireName());
      if (client == null || client.id() == null || !client.id().matches("[a-z0-9-]{3,64}")) {
        throw invalid(key + ".id", "must be 3 to 64 lowercase letters, digits, or dashes");
      }
      if (client.secret() == null || client.secret().length() < 32) {
        throw invalid(key + ".secret", "must have at least 32 characters");
      }
    }
    clients = Map.copyOf(clients);
  }

  private static IllegalArgumentException invalid(String key, String reason) {
    return new IllegalArgumentException("invalid configuration: " + key + ": " + reason);
  }
}
