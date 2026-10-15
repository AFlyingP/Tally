package tally.monitor.ingest;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;
import tally.monitor.cases.AlertStore;
import tally.monitor.cases.CaseAssigner;
import tally.monitor.rules.RuleEngine;
import tally.monitor.rules.RuleProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EventConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(EventConsumer.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DataSource dataSource;
  private final RuleProperties properties;
  private final MovementStore movements;
  private final RuleEngine rules;
  private final AlertStore alerts;
  private final CaseAssigner cases;
  private final Clock clock;

  public EventConsumer(
      DataSource dataSource,
      RuleProperties properties,
      MovementStore movements,
      RuleEngine rules,
      AlertStore alerts,
      CaseAssigner cases,
      Clock clock) {
    this.dataSource = dataSource;
    this.properties = properties;
    this.movements = movements;
    this.rules = rules;
    this.alerts = alerts;
    this.cases = cases;
    this.clock = clock;
  }

  @Bean
  ConsumerFactory<String, String> monitorConsumerFactory() {
    Map<String, Object> settings = new HashMap<>();
    settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrap());
    settings.put(ConsumerConfig.GROUP_ID_CONFIG, properties.kafka().group());
    settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    settings.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
    settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    return new DefaultKafkaConsumerFactory<>(settings);
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> monitorConsumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(monitorConsumerFactory);
    factory.setCommonErrorHandler(
        new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    return factory;
  }

  @KafkaListener(
      topics = "${tally.monitor.kafka.topic}",
      groupId = "${tally.monitor.kafka.group}",
      concurrency = "1")
  public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment)
      throws Exception {
    String previousRequestId = MDC.get("request_id");
    Header requestHeader = record.headers().lastHeader("request_id");
    if (requestHeader != null) {
      MDC.put("request_id", new String(requestHeader.value(), StandardCharsets.UTF_8));
    }
    try (Connection connection = dataSource.getConnection()) {
      try {
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        process(connection, record);
        connection.commit();
      } catch (Exception failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
      acknowledgment.acknowledge();
    } finally {
      if (previousRequestId == null) {
        MDC.remove("request_id");
      } else {
        MDC.put("request_id", previousRequestId);
      }
    }
  }

  List<Movement> movementsOf(TransactionEvent event) {
    if (!List.of("DEPOSIT", "TRANSFER", "HOLD_CAPTURE").contains(event.kind())) {
      return List.of();
    }
    List<Movement> result = new ArrayList<>();
    for (TransactionEvent.Entry entry : event.entries()) {
      if (!"CUSTOMER".equals(entry.accountKind())) {
        continue;
      }
      String direction = entry.side().equals(entry.normalSide()) ? "IN" : "OUT";
      boolean counted =
          ("OUT".equals(direction) && List.of("TRANSFER", "HOLD_CAPTURE").contains(event.kind()))
              || ("IN".equals(direction) && List.of("DEPOSIT", "TRANSFER").contains(event.kind()));
      boolean r2Candidate =
          counted
              && "OUT".equals(direction)
              && RuleEngine.isR2Candidate(entry.amount(), properties.r2());
      result.add(
          new Movement(
              event.transactionId(),
              entry.accountId(),
              direction,
              event.kind(),
              entry.amount(),
              event.currency(),
              counted,
              r2Candidate,
              event.postedAt()));
    }
    return List.copyOf(result);
  }

  private void process(Connection connection, ConsumerRecord<String, String> record)
      throws SQLException {
    Optional<TransactionEvent> parsed = TransactionEvent.parse(record.value());
    UUID eventId =
        parsed.map(TransactionEvent::eventId).orElseGet(() -> readEventId(record.value()));
    if (eventId != null && !movements.markProcessed(connection, eventId, clock.instant())) {
      return;
    }
    if (parsed.isEmpty()) {
      LOG.error("unreadable event at partition={} offset={}", record.partition(), record.offset());
      return;
    }

    TransactionEvent event = parsed.orElseThrow();
    for (Movement movement : movementsOf(event)) {
      if (!movements.insert(connection, event.eventId(), movement)) {
        continue;
      }
      List<Movement> window = window(connection, movement);
      for (RuleEngine.AlertCandidate candidate :
          rules.evaluate(movement.accountId(), window, movement)) {
        OptionalLong alertId = alerts.insert(connection, candidate, clock.instant());
        if (alertId.isPresent()) {
          cases.assign(connection, alertId.getAsLong(), candidate);
        }
      }
    }
  }

  private List<Movement> window(Connection connection, Movement movement) throws SQLException {
    long shortSeconds = Math.max(properties.r1().windowSeconds(), properties.r3().windowSeconds());
    long r2Seconds = properties.r2().windowSeconds();
    Instant eventTime = movement.eventTime();
    return movements.window(
        connection,
        movement.accountId(),
        eventTime.minusSeconds(shortSeconds),
        eventTime.plusSeconds(shortSeconds),
        eventTime.minusSeconds(r2Seconds),
        eventTime.plusSeconds(r2Seconds));
  }

  private static UUID readEventId(String value) {
    if (value == null) {
      return null;
    }
    try {
      JsonNode root = JSON.readTree(value);
      JsonNode eventId = root == null ? null : root.get("event_id");
      if (eventId == null || !eventId.isString()) {
        return null;
      }
      String valueText = eventId.stringValue();
      UUID id = UUID.fromString(valueText);
      return id.toString().equals(valueText) ? id : null;
    } catch (JacksonException | IllegalArgumentException e) {
      return null;
    }
  }
}
