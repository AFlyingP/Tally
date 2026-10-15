package tally.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.kafka.KafkaContainer;
import tally.ledger.config.LedgerProperties;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxRelayIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final KafkaContainer KAFKA =
      new KafkaContainer("apache/kafka:4.2.2").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

  private ConfigurableApplicationContext context;
  private OutboxRelay relay;
  private String topic;

  @BeforeAll
  void start() {
    KAFKA.start();
    context =
        startApp(
            Map.of(
                "tally.ledger.kafka.bootstrap", KAFKA.getBootstrapServers(),
                "tally.ledger.relay.batch", "5",
                "spring.datasource.hikari.maximum-pool-size", "4"));
    relay = context.getBean(OutboxRelay.class);
    topic = context.getBean(LedgerProperties.class).kafka().topic();
  }

  @BeforeEach
  void drainExistingRows() {
    assertThat(context.getBean(TopicSetup.class).ensure()).isTrue();
    drain(relay);
  }

  @AfterEach
  void releaseLock() {
    relay.release();
  }

  @AfterAll
  void stop() {
    try {
      if (context != null) {
        context.close();
      }
    } finally {
      KAFKA.close();
    }
  }

  @Test
  void publishesEveryOutboxRowOnce() throws Exception {
    List<UUID> transactions = transfers(20);
    List<Row> expected = rows(transactions);
    assertThat(expected).hasSize(21);

    drain(relay);

    List<ConsumerRecord<String, String>> records = records(expected);
    assertThat(records).hasSize(21);
    assertPublished(transactions);
    for (Row row : expected) {
      List<ConsumerRecord<String, String>> matching =
          records.stream().filter(record -> eventId(record).equals(row.eventId())).toList();
      assertThat(matching).hasSize(1);
      ConsumerRecord<String, String> record = matching.getFirst();
      assertThat(record.key()).isEqualTo(row.keyAccountId().toString());
      assertThat(record.value()).isEqualTo(row.payload());
      assertThat(header(record, "event_id")).isEqualTo(row.eventId().toString());
      assertThat(header(record, "request_id")).isEqualTo(row.requestId());
    }
  }

  @Test
  void preservesOutboxOrderPerKey() throws Exception {
    UUID first = account();
    UUID second = account();
    UUID destination = account();
    List<UUID> transactions = new ArrayList<>();
    transactions.add(deposit(first));
    transactions.add(deposit(second));
    for (int i = 0; i < 30; i++) {
      transactions.add(transfer(first, destination));
      transactions.add(transfer(second, destination));
    }
    List<Row> expected = rows(transactions);
    assertThat(expected).hasSize(62);

    drain(relay);

    List<ConsumerRecord<String, String>> records = records(expected);
    assertThat(records).hasSize(62);
    for (UUID account : List.of(first, second)) {
      List<UUID> outboxOrder =
          expected.stream()
              .filter(row -> row.keyAccountId().equals(account))
              .map(Row::eventId)
              .toList();
      List<UUID> topicOrder =
          records.stream()
              .filter(record -> record.key().equals(account.toString()))
              .map(OutboxRelayIT::eventId)
              .toList();
      assertThat(topicOrder).containsExactlyElementsOf(outboxOrder);
    }
    assertPublished(transactions);
  }

  @Test
  void marksOnlyAcknowledgedPrefixWhenBrokerIsDown() throws Exception {
    List<UUID> transactions = transfers(3);
    relay.release();
    try (ConfigurableApplicationContext down =
        startApp(
            Map.of(
                "tally.ledger.kafka.bootstrap", "localhost:1",
                "spring.datasource.hikari.maximum-pool-size", "4"))) {
      long started = System.nanoTime();
      assertThat(down.getBean(OutboxRelay.class).runOnce()).isZero();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
      assertThat(rows(transactions)).hasSize(4).allMatch(row -> !row.published());
    }
  }

  @Test
  void crashAfterSendRepublishesTheBatch() throws Exception {
    List<UUID> transactions = transfers(11);
    List<Row> expected = rows(transactions);
    OutboxRelay.Batch batch;
    try (Connection connection = context.getBean(DataSource.class).getConnection()) {
      batch = relay.sendBatch(connection);
    }
    assertThat(batch.acknowledged())
        .containsExactlyElementsOf(expected.stream().limit(5).map(Row::id).toList());
    assertThat(rows(transactions)).allMatch(row -> !row.published());

    drain(relay);

    List<ConsumerRecord<String, String>> records = records(expected);
    assertThat(records).hasSize(expected.size() + 5);
    for (Row row : expected) {
      long copies =
          records.stream().filter(record -> eventId(record).equals(row.eventId())).count();
      assertThat(copies).isEqualTo(batch.acknowledged().contains(row.id()) ? 2 : 1);
    }
    assertPublished(transactions);
  }

  @Test
  void secondRelayDoesNotPublishWhileFirstHoldsTheLock() throws Exception {
    List<UUID> transactions = transfers(11);
    List<Row> expected = rows(transactions);
    assertThat(relay.runOnce()).isEqualTo(5);
    try (OutboxRelay second =
        new OutboxRelay(
            context.getBean(DataSource.class),
            context.getBean(OutboxStore.class),
            context.getBean(TopicSetup.class),
            context.getBean(LedgerProperties.class),
            context.getBean(Clock.class))) {
      assertThat(second.runOnce()).isZero();
      assertThat(rows(transactions).stream().filter(Row::published)).hasSize(5);

      relay.release();

      assertThat(second.runOnce()).isEqualTo(5);
      assertThat(second.runOnce()).isEqualTo(2);
      assertThat(second.runOnce()).isZero();
      assertPublished(transactions);
      assertThat(records(expected).stream().map(OutboxRelayIT::eventId))
          .containsExactlyInAnyOrderElementsOf(expected.stream().map(Row::eventId).toList());
    }
  }

  @Test
  void createsTopicWithThreePartitions() throws Exception {
    assertThat(context.getBean(TopicSetup.class).ensure()).isTrue();
    try (AdminClient admin =
        AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      var description =
          admin.describeTopics(List.of(topic)).allTopicNames().get(10, TimeUnit.SECONDS).get(topic);
      assertThat(description.partitions()).hasSize(3);
      assertThat(description.partitions())
          .allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
    }
  }

  @Test
  void batchLimitIsRespected() throws Exception {
    List<UUID> transactions = transfers(11);
    assertThat(rows(transactions)).hasSize(12).allMatch(row -> !row.published());

    assertThat(relay.runOnce()).isEqualTo(5);
    assertThat(relay.pollMs()).isZero();
    assertThat(rows(transactions).stream().filter(Row::published)).hasSize(5);
    assertThat(relay.runOnce()).isEqualTo(5);
    assertThat(relay.pollMs()).isZero();
    assertThat(rows(transactions).stream().filter(Row::published)).hasSize(10);
    assertThat(relay.runOnce()).isEqualTo(2);
    assertThat(relay.pollMs()).isEqualTo(200);
    assertThat(relay.runOnce()).isZero();
    assertThat(relay.pollMs()).isEqualTo(200);
    assertPublished(transactions);
  }

  private static void drain(OutboxRelay relay) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (relay.runOnce() > 0) {
      assertThat(System.nanoTime()).isLessThan(deadline);
    }
  }

  private List<UUID> transfers(int count) {
    UUID source = account();
    UUID destination = account();
    List<UUID> transactions = new ArrayList<>();
    transactions.add(deposit(source));
    for (int i = 0; i < count; i++) {
      transactions.add(transfer(source, destination));
    }
    return transactions;
  }

  private UUID account() {
    return postId("/v1/accounts", "{\"currency\":\"EUR\"}");
  }

  private UUID deposit(UUID account) {
    return postId(
        "/v1/deposits",
        "{\"account_id\":\"%s\",\"amount\":1000,\"currency\":\"EUR\"}".formatted(account));
  }

  private UUID transfer(UUID source, UUID destination) {
    return postId(
        "/v1/transfers",
        """
        {"source_account_id":"%s","destination_account_id":"%s","amount":1,"currency":"EUR"}
        """
            .formatted(source, destination));
  }

  private UUID postId(String path, String body) {
    var response =
        send(
            request(url(context, path), token(Role.OPERATOR))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private List<Row> rows(List<UUID> transactions) throws SQLException {
    try (Connection connection = appJdbc()) {
      var ids = connection.createArrayOf("uuid", transactions.toArray(UUID[]::new));
      try (var select =
          connection.prepareStatement(
              "SELECT id, event_id, key_account_id, request_id, payload, published_at"
                  + " FROM ledger.outbox WHERE transaction_id = ANY(?) ORDER BY id")) {
        select.setArray(1, ids);
        try (var result = select.executeQuery()) {
          List<Row> rows = new ArrayList<>();
          while (result.next()) {
            rows.add(
                new Row(
                    result.getLong("id"),
                    result.getObject("event_id", UUID.class),
                    result.getObject("key_account_id", UUID.class),
                    result.getString("request_id"),
                    result.getString("payload"),
                    result.getObject("published_at") != null));
          }
          return rows;
        }
      } finally {
        ids.free();
      }
    }
  }

  private void assertPublished(List<UUID> transactions) throws SQLException {
    assertThat(rows(transactions)).hasSize(transactions.size()).allMatch(Row::published);
  }

  private List<ConsumerRecord<String, String>> records(List<Row> expected) {
    Set<UUID> ids = new HashSet<>(expected.stream().map(Row::eventId).toList());
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                10000))) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic).stream()
              .map(info -> new TopicPartition(topic, info.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
      List<ConsumerRecord<String, String>> records = new ArrayList<>();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (partitions.stream()
          .anyMatch(partition -> consumer.position(partition) < ends.get(partition))) {
        assertThat(System.nanoTime()).isLessThan(deadline);
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
          if (ids.contains(eventId(record))) {
            records.add(record);
          }
        }
      }
      return records;
    }
  }

  private static UUID eventId(ConsumerRecord<String, String> record) {
    return UUID.fromString(header(record, "event_id"));
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    assertThat(header).isNotNull();
    return new String(header.value(), StandardCharsets.UTF_8);
  }

  private record Row(
      long id,
      UUID eventId,
      UUID keyAccountId,
      String requestId,
      String payload,
      boolean published) {}
}
