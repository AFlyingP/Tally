package tally.ledger.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import tally.ledger.config.LedgerProperties;

@Tag("integration")
class ReadinessChecksIT {

  @Test
  void kafkaCheckUpAndDown() {
    try (KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.2")) {
      kafka.start();
      KafkaReadinessCheck up =
          new KafkaReadinessCheck(properties(kafka.getBootstrapServers(), "http://localhost:1"));
      assertThat(up.name()).isEqualTo("kafka");
      assertThat(up.up()).isTrue();
    }

    KafkaReadinessCheck down =
        new KafkaReadinessCheck(properties("localhost:1", "http://localhost:1"));
    long start = System.nanoTime();
    assertThat(down.up()).isFalse();
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);

    KafkaReadinessCheck unresolvable =
        new KafkaReadinessCheck(properties("no-such-host.invalid:9092", "http://localhost:1"));
    assertThat(unresolvable.up()).isFalse();
  }

  @Test
  void extbankCheckUpAndDown() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    AtomicInteger status = new AtomicInteger(200);
    server.createContext(
        "/health",
        exchange -> {
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.start();
    ExtbankReadinessCheck check =
        new ExtbankReadinessCheck(
            properties("localhost:1", "http://localhost:" + server.getAddress().getPort()));
    try {
      assertThat(check.name()).isEqualTo("extbank");
      assertThat(check.up()).isTrue();

      status.set(503);
      assertThat(check.up()).isFalse();
    } finally {
      server.stop(0);
    }

    long start = System.nanoTime();
    assertThat(check.up()).isFalse();
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);
  }

  private static LedgerProperties properties(String bootstrap, String extbankUrl) {
    return new LedgerProperties(
        "pessimistic",
        Set.of("relay", "orchestrator"),
        new LedgerProperties.Kafka(bootstrap, "tally.transactions.v1"),
        new LedgerProperties.Extbank(extbankUrl, 2000, "test-api-key"));
  }
}
