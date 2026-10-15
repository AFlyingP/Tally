package tally.ledger.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tally.ledger.config.LedgerProperties;

@Component
public final class TopicSetup {

  private static final Logger log = LoggerFactory.getLogger(TopicSetup.class);
  private static final int TIMEOUT_MS = 10000;

  private final LedgerProperties properties;
  private boolean ready;

  public TopicSetup(LedgerProperties properties) {
    this.properties = properties;
  }

  @EventListener
  public void start(ApplicationReadyEvent event) {
    if (properties.has("relay")) {
      ensure();
    }
  }

  public synchronized boolean ensure() {
    if (ready) {
      return true;
    }
    try {
      AdminClient admin =
          AdminClient.create(
              Map.of(
                  AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrap(),
                  AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, TIMEOUT_MS,
                  AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, TIMEOUT_MS));
      try {
        NewTopic topic =
            new NewTopic(properties.kafka().topic(), 3, (short) 1)
                .configs(
                    Map.of(
                        TopicConfig.CLEANUP_POLICY_CONFIG,
                        TopicConfig.CLEANUP_POLICY_DELETE,
                        TopicConfig.RETENTION_MS_CONFIG,
                        "-1"));
        admin
            .createTopics(List.of(topic), new CreateTopicsOptions().timeoutMs(TIMEOUT_MS))
            .all()
            .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        ready = true;
      } finally {
        admin.close(Duration.ZERO);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      log.warn("topic setup failed: {}", exception.getMessage());
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof TopicExistsException) {
        ready = true;
      } else {
        log.warn("topic setup failed: {}", exception.getCause().getMessage());
      }
    } catch (Exception exception) {
      log.warn("topic setup failed: {}", exception.getMessage());
    }
    return ready;
  }
}
