package tally.ledger.config;

import java.time.LocalTime;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The tally.ledger.* settings, checked once at startup. */
@ConfigurationProperties("tally.ledger")
public record LedgerProperties(
    String strategy,
    Set<String> roles,
    Kafka kafka,
    Extbank extbank,
    Retry retry,
    Idempotency idempotency,
    long lockWaitMs,
    Expiry expiry,
    String failpoint,
    Scheduler scheduler,
    Payments payments,
    Relay relay,
    Recon recon,
    Sealer sealer) {

  public static final Set<String> KNOWN_ROLES =
      Set.of("api", "scheduler", "orchestrator", "relay", "expiry", "recon", "sealer");

  public record Kafka(String bootstrap, String topic) {}

  public record Extbank(String url, long timeoutMs, String apiKey) {}

  public record Idempotency(long waitMs) {

    public Idempotency {
      if (waitMs < 100 || waitMs > 60000) {
        throw invalid("tally.ledger.idempotency.wait-ms", "must be between 100 and 60000");
      }
    }
  }

  public record Expiry(long pollMs) {

    public Expiry {
      if (pollMs < 50 || pollMs > 600000) {
        throw invalid("tally.ledger.expiry.poll-ms", "must be between 50 and 600000");
      }
    }
  }

  public record Scheduler(long pollMs, long retryIntervalMs) {

    public Scheduler {
      if (pollMs < 50 || pollMs > 600000) {
        throw invalid("tally.ledger.scheduler.poll-ms", "must be between 50 and 600000");
      }
      if (retryIntervalMs < 100 || retryIntervalMs > 86400000) {
        throw invalid(
            "tally.ledger.scheduler.retry-interval-ms", "must be between 100 and 86400000");
      }
    }
  }

  public record Payments(long pollMs, long leaseMs, long[] statusBackoffMs) {

    public Payments {
      if (pollMs < 50 || pollMs > 600000) {
        throw invalid("tally.ledger.payments.poll-ms", "must be between 50 and 600000");
      }
      if (leaseMs < 1000 || leaseMs > 600000) {
        throw invalid("tally.ledger.payments.lease-ms", "must be between 1000 and 600000");
      }
      if (statusBackoffMs == null || statusBackoffMs.length != 8) {
        throw invalid("tally.ledger.payments.status-backoff-ms", "must contain exactly 8 items");
      }
      for (long delay : statusBackoffMs) {
        if (delay < 10 || delay > 600000) {
          throw invalid("tally.ledger.payments.status-backoff-ms", "must be between 10 and 600000");
        }
      }
      statusBackoffMs = statusBackoffMs.clone();
    }

    @Override
    public long[] statusBackoffMs() {
      return statusBackoffMs.clone();
    }
  }

  public record Relay(long pollMs, int batch) {

    public Relay {
      if (pollMs < 20 || pollMs > 60000) {
        throw invalid("tally.ledger.relay.poll-ms", "must be between 20 and 60000");
      }
      if (batch < 1 || batch > 5000) {
        throw invalid("tally.ledger.relay.batch", "must be between 1 and 5000");
      }
    }
  }

  public record Recon(LocalTime timeUtc) {

    public Recon {
      if (timeUtc == null || timeUtc.getSecond() != 0 || timeUtc.getNano() != 0) {
        throw invalid("tally.ledger.recon.time-utc", "must be a valid HH:mm time");
      }
    }
  }

  public record Sealer(long pollMs) {

    public Sealer {
      if (pollMs < 50 || pollMs > 60000) {
        throw invalid("tally.ledger.sealer.poll-ms", "must be between 50 and 60000");
      }
    }
  }

  public record Retry(int maxAttempts, long baseMs, long capMs) {

    public Retry {
      if (maxAttempts < 1 || maxAttempts > 50) {
        throw invalid("tally.ledger.retry.max-attempts", "must be between 1 and 50");
      }
      if (baseMs < 1 || baseMs > 1000) {
        throw invalid("tally.ledger.retry.base-ms", "must be between 1 and 1000");
      }
      if (capMs < 1 || capMs > 10000) {
        throw invalid("tally.ledger.retry.cap-ms", "must be between 1 and 10000");
      }
    }
  }

  public LedgerProperties {
    if (lockWaitMs < 100 || lockWaitMs > 60000) {
      throw invalid("tally.ledger.lock-wait-ms", "must be between 100 and 60000");
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
