package tally.ledger.health;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.KafkaException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tally.ledger.config.LedgerProperties;
import tally.platform.web.ReadinessCheck;

@Component
@ConditionalOnExpression("'${tally.ledger.roles}'.contains('relay')")
public class KafkaReadinessCheck implements ReadinessCheck {

  private final String bootstrap;

  public KafkaReadinessCheck(LedgerProperties properties) {
    this.bootstrap = properties.kafka().bootstrap();
  }

  @Override
  public String name() {
    return "kafka";
  }

  @Override
  public boolean up() {
    Map<String, Object> config =
        Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 1000);
    // A client per call: a long-lived one keeps reconnecting and logging while the broker is down.
    AdminClient admin = AdminClient.create(config);
    try {
      admin.describeCluster().nodes().get(1000, TimeUnit.MILLISECONDS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (ExecutionException | TimeoutException | KafkaException e) {
      return false;
    } finally {
      admin.close(Duration.ZERO);
    }
  }
}
