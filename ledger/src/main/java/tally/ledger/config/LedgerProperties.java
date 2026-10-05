package tally.ledger.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The tally.ledger.* settings, checked once at startup. */
@ConfigurationProperties("tally.ledger")
public record LedgerProperties(String strategy, Set<String> roles, Kafka kafka, Extbank extbank) {

  public static final Set<String> KNOWN_ROLES =
      Set.of("api", "scheduler", "orchestrator", "relay", "expiry", "recon", "sealer");

  public record Kafka(String bootstrap, String topic) {}

  public record Extbank(String url, long timeoutMs, String apiKey) {}

  public LedgerProperties {
    if (strategy == null || strategy.isBlank()) {
      throw invalid("tally.ledger.strategy", "must not be empty");
    }
    if (roles == null || roles.isEmpty()) {
      throw invalid("tally.ledger.roles", "at least one role is required");
    }
    for (String role : roles) {
      if (!KNOWN_ROLES.contains(role)) {
        throw invalid("tally.ledger.roles", "unknown role " + role);
      }
    }
    if (kafka.bootstrap() == null || !kafka.bootstrap().matches("[^:\\s]+:\\d+")) {
      throw invalid("tally.ledger.kafka.bootstrap", "must be host:port");
    }
    if (kafka.topic() == null || kafka.topic().isBlank()) {
      throw invalid("tally.ledger.kafka.topic", "must not be empty");
    }
    if (extbank.url() == null || !extbank.url().startsWith("http://")) {
      throw invalid("tally.ledger.extbank.url", "must be an http URL");
    }
    if (extbank.timeoutMs() < 100 || extbank.timeoutMs() > 30000) {
      throw invalid("tally.ledger.extbank.timeout-ms", "must be between 100 and 30000");
    }
    boolean callsExtbank = roles.contains("orchestrator") || roles.contains("recon");
    if (callsExtbank && (extbank.apiKey() == null || extbank.apiKey().isBlank())) {
      throw invalid("tally.ledger.extbank.api-key", "is required with role orchestrator or recon");
    }
    roles = Set.copyOf(roles);
  }

  public boolean has(String role) {
    return roles.contains(role);
  }

  private static IllegalArgumentException invalid(String key, String reason) {
    return new IllegalArgumentException("invalid configuration: " + key + ": " + reason);
  }
}
