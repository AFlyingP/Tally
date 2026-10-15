package tally.ledger.outbox;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tally.ledger.config.JobConfig.Job;
import tally.ledger.config.LedgerProperties;
import tally.ledger.failpoint.Failpoints;
import tally.ledger.store.AdvisoryLock;

@Component
public final class OutboxRelay implements Job, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final int SEND_TIMEOUT_MS = 10000;

  private final DataSource dataSource;
  private final OutboxStore outbox;
  private final TopicSetup topicSetup;
  private final LedgerProperties properties;
  private final Clock clock;
  private final AdvisoryLock lock;
  private KafkaProducer<String, String> producer;
  private boolean fullBatch;

  public OutboxRelay(
      DataSource dataSource,
      OutboxStore outbox,
      TopicSetup topicSetup,
      LedgerProperties properties,
      Clock clock) {
    this.dataSource = dataSource;
    this.outbox = outbox;
    this.topicSetup = topicSetup;
    this.properties = properties;
    this.clock = clock;
    lock = new AdvisoryLock(dataSource, 7411001L);
  }

  @Override
  public String name() {
    return "relay";
  }

  @Override
  public String role() {
    return "relay";
  }

  @Override
  public long pollMs() {
    return fullBatch ? 0 : properties.relay().pollMs();
  }

  @Override
  public int runOnce() {
    fullBatch = false;
    try {
      if (!lock.tryAcquire() || !topicSetup.ensure()) {
        return 0;
      }
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        try {
          Batch batch = sendBatch(connection);
          List<Long> ids = batch.acknowledged();
          if (batch.selected() > 0) {
            Failpoints.hit("relay.after_send");
            outbox.markPublished(connection, ids, clock.instant().truncatedTo(ChronoUnit.MICROS));
          }
          connection.commit();
          fullBatch = ids.size() == properties.relay().batch();
          return ids.size();
        } catch (SQLException | RuntimeException exception) {
          connection.rollback();
          throw exception;
        }
      }
    } catch (SQLException | RuntimeException exception) {
      log.warn("relay failed: {}", exception.getMessage());
      return 0;
    }
  }

  /** Sends a batch on the caller's connection while the caller holds the relay lock. */
  Batch sendBatch(Connection connection) throws SQLException {
    List<OutboxStore.Pending> rows = outbox.unpublished(connection, properties.relay().batch());
    if (rows.isEmpty()) {
      return new Batch(List.of(), 0);
    }
    List<Future<RecordMetadata>> sends = new ArrayList<>();
    for (OutboxStore.Pending row : rows) {
      ProducerRecord<String, String> record =
          new ProducerRecord<>(
              properties.kafka().topic(), row.keyAccountId().toString(), row.payload());
      record.headers().add("event_id", row.eventId().toString().getBytes(StandardCharsets.UTF_8));
      record.headers().add("request_id", row.requestId().getBytes(StandardCharsets.UTF_8));
      try {
        sends.add(producer().send(record));
      } catch (KafkaException exception) {
        sends.add(CompletableFuture.failedFuture(exception));
      }
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEND_TIMEOUT_MS);
    List<Long> acknowledged = new ArrayList<>();
    boolean prefix = true;
    for (int i = 0; i < sends.size(); i++) {
      try {
        sends.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (prefix) {
          acknowledged.add(rows.get(i).id());
        }
      } catch (ExecutionException | TimeoutException exception) {
        log.warn("send failed: {}", exception.getMessage());
        prefix = false;
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        log.warn("send failed: {}", exception.getMessage());
        break;
      }
    }
    return new Batch(List.copyOf(acknowledged), rows.size());
  }

  record Batch(List<Long> acknowledged, int selected) {}

  public void release() {
    lock.release();
  }

  @Override
  @PreDestroy
  public void close() {
    try {
      if (producer != null) {
        producer.close(Duration.ofMillis(SEND_TIMEOUT_MS));
      }
    } finally {
      producer = null;
      release();
    }
  }

  private KafkaProducer<String, String> producer() {
    if (producer == null) {
      producer =
          new KafkaProducer<>(
              Map.ofEntries(
                  Map.entry(
                      ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrap()),
                  Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
                  Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
                  Map.entry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5),
                  Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, SEND_TIMEOUT_MS),
                  Map.entry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000),
                  Map.entry(ProducerConfig.LINGER_MS_CONFIG, 5),
                  Map.entry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "none"),
                  Map.entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
                  Map.entry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
    }
    return producer;
  }
}
