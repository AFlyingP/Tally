package tally.monitor.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.DockerClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.DockerClientFactory;
import tally.monitor.testing.MonitorIT;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(OutputCaptureExtension.class)
class EventConsumerIT extends MonitorIT {

  private static final Instant BASE = Instant.parse("2026-03-10T00:00:00Z");
  private static final DateTimeFormatter POSTED_AT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void transferCreatesOutAndInMovements() throws Exception {
    UUID source = UUID.randomUUID();
    UUID destination = UUID.randomUUID();
    Published event =
        event(
            "TRANSFER",
            source,
            250,
            BASE,
            null,
            entry(source, "CUSTOMER", "CREDIT", "DEBIT", 250),
            entry(destination, "CUSTOMER", "CREDIT", "CREDIT", 250));

    publish(event);
    awaitProcessed(List.of(event));

    assertThat(movements(event.transactionId()))
        .containsExactlyInAnyOrder(
            new MovementRow("OUT", "TRANSFER", 250, "EUR", true, false, BASE),
            new MovementRow("IN", "TRANSFER", 250, "EUR", true, false, BASE));
  }

  @Test
  void depositCreatesOneInMovement() throws Exception {
    UUID customer = UUID.randomUUID();
    Published event =
        event(
            "DEPOSIT",
            customer,
            500,
            BASE,
            null,
            entry(customer, "CUSTOMER", "CREDIT", "CREDIT", 500),
            entry(UUID.randomUUID(), "SETTLEMENT", "DEBIT", "DEBIT", 500));

    publish(event);
    awaitProcessed(List.of(event));

    assertThat(movements(event.transactionId()))
        .containsExactly(new MovementRow("IN", "DEPOSIT", 500, "EUR", true, false, BASE));
  }

  @Test
  void withdrawalAndReversalCreateNoMovement() throws Exception {
    UUID customer = UUID.randomUUID();
    UUID settlement = UUID.randomUUID();
    Published withdrawal =
        event(
            "WITHDRAWAL",
            customer,
            300,
            BASE,
            null,
            entry(customer, "CUSTOMER", "CREDIT", "DEBIT", 300),
            entry(settlement, "SETTLEMENT", "DEBIT", "CREDIT", 300));
    Published reversal =
        event(
            "REVERSAL",
            customer,
            300,
            BASE.plusSeconds(1),
            withdrawal.transactionId(),
            entry(customer, "CUSTOMER", "CREDIT", "CREDIT", 300),
            entry(settlement, "SETTLEMENT", "DEBIT", "DEBIT", 300));

    publish(withdrawal, reversal);
    awaitProcessed(List.of(withdrawal, reversal));

    assertThat(movements(withdrawal.transactionId())).isEmpty();
    assertThat(movements(reversal.transactionId())).isEmpty();
  }

  @Test
  void holdCaptureToClearingCreatesOneCountedOut() throws Exception {
    UUID customer = UUID.randomUUID();
    Published event =
        event(
            "HOLD_CAPTURE",
            customer,
            700,
            BASE,
            null,
            entry(customer, "CUSTOMER", "CREDIT", "DEBIT", 700),
            entry(UUID.randomUUID(), "CLEARING", "CREDIT", "CREDIT", 700));

    publish(event);
    awaitProcessed(List.of(event));

    assertThat(movements(event.transactionId()))
        .containsExactly(new MovementRow("OUT", "HOLD_CAPTURE", 700, "EUR", true, false, BASE));
  }

  @Test
  void duplicateDeliveryIsDiscarded() throws Exception {
    UUID source = UUID.randomUUID();
    Published event = transfer(source, UUID.randomUUID(), BASE);
    publish(event);
    awaitProcessed(List.of(event));
    long movementsBefore = movementCount(source);
    long alertsBefore = alertCount(source);
    List<MovementRow> originalMovements = movements(event.transactionId());
    Instant laterAt = BASE.plusSeconds(1);
    Published later = transfer(source, UUID.randomUUID(), laterAt);

    publish(event, later);
    awaitProcessed(List.of(event, later));

    assertThat(processedCount(List.of(event))).isEqualTo(1);
    assertThat(movements(event.transactionId())).isEqualTo(originalMovements);
    assertThat(movementCount(source)).isEqualTo(movementsBefore + 1);
    assertThat(alertCount(source)).isEqualTo(alertsBefore);
  }

  @Test
  void velocityBurstCreatesAlertsAndOneCase() throws Exception {
    UUID source = UUID.randomUUID();
    List<Published> events = transferBurst(source);

    publish(events.toArray(Published[]::new));
    awaitProcessed(events);

    List<String> expected = triggerKeys(events);
    assertThat(alerts(source)).extracting(AlertRow::rule).containsExactly("R1", "R1");
    assertThat(alerts(source)).extracting(AlertRow::triggerKey).containsExactlyElementsOf(expected);
    assertThat(cases(source)).containsExactly(new CaseRow("R1", "OPEN", 2));
    assertThat(caseEvents(source)).containsExactly("OPENED", "ALERT_ADDED");
  }

  @Test
  void outOfOrderDeliveryGivesSameAlerts() throws Exception {
    UUID source = UUID.randomUUID();
    List<Published> events = transferBurst(source);
    List<Published> reversed = new ArrayList<>(events);
    Collections.reverse(reversed);

    publish(reversed.toArray(Published[]::new));
    awaitProcessed(events);

    assertThat(alerts(source))
        .extracting(AlertRow::triggerKey)
        .containsExactlyInAnyOrderElementsOf(triggerKeys(events));
  }

  @Test
  void unreadableEventIsSkipped(CapturedOutput output) throws Exception {
    UUID source = UUID.randomUUID();
    Published unreadable = new Published(source, UUID.randomUUID(), UUID.randomUUID(), "not json");
    Published valid =
        event(
            "DEPOSIT",
            source,
            100,
            BASE,
            null,
            entry(source, "CUSTOMER", "CREDIT", "CREDIT", 100),
            entry(UUID.randomUUID(), "SETTLEMENT", "DEBIT", "DEBIT", 100));

    publish(unreadable, valid);
    awaitProcessed(List.of(valid));

    assertThat(movements(valid.transactionId()))
        .containsExactly(new MovementRow("IN", "DEPOSIT", 100, "EUR", true, false, BASE));
    assertThat(output.getAll()).contains("unreadable event");
  }

  @Test
  void databaseOutageIsRetriedWithoutLoss() throws Exception {
    UUID source = UUID.randomUUID();
    Published event =
        event(
            "DEPOSIT",
            source,
            100,
            BASE,
            null,
            entry(source, "CUSTOMER", "CREDIT", "CREDIT", 100),
            entry(UUID.randomUUID(), "SETTLEMENT", "DEBIT", "DEBIT", 100));
    String topic = topic();
    DockerClient dockerClient = DockerClientFactory.instance().client();

    dockerClient.pauseContainerCmd(POSTGRES.getContainerId()).exec();
    try {
      publish(topic, event);
      Thread.sleep(3000);
    } finally {
      dockerClient.unpauseContainerCmd(POSTGRES.getContainerId()).exec();
    }
    awaitProcessed(List.of(event));

    assertThat(processedCount(List.of(event))).isEqualTo(1);
    assertThat(movements(event.transactionId()))
        .containsExactly(new MovementRow("IN", "DEPOSIT", 100, "EUR", true, false, BASE));
  }

  @Test
  void consumerDoesNotReadLedgerSchema() throws Exception {
    try (Connection connection = appJdbc();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT has_schema_privilege('monitor_app', 'ledger', 'USAGE')");
        ResultSet row = select.executeQuery()) {
      assertThat(row.next()).isTrue();
      assertThat(row.getBoolean(1)).isFalse();
    }
  }

  private static List<Published> transferBurst(UUID source) {
    List<Published> events = new ArrayList<>();
    UUID destination = UUID.randomUUID();
    for (int i = 0; i < 12; i++) {
      events.add(transfer(source, destination, BASE.plusSeconds(i * 10L)));
    }
    return List.copyOf(events);
  }

  private static List<String> triggerKeys(List<Published> events) {
    return events.subList(10, 12).stream().map(event -> event.transactionId().toString()).toList();
  }

  private static Published transfer(UUID source, UUID destination, Instant at) {
    return event(
        "TRANSFER",
        source,
        250,
        at,
        null,
        entry(source, "CUSTOMER", "CREDIT", "DEBIT", 250),
        entry(destination, "CUSTOMER", "CREDIT", "CREDIT", 250));
  }

  private static EntrySpec entry(
      UUID accountId, String accountKind, String normalSide, String side, long amount) {
    return new EntrySpec(accountId, accountKind, normalSide, side, amount);
  }

  private static Published event(
      String kind, UUID keyAccount, long amount, Instant at, UUID reverses, EntrySpec... entries) {
    UUID eventId = UUID.randomUUID();
    UUID transactionId = UUID.randomUUID();
    ObjectNode root = JSON.createObjectNode();
    root.put("event_id", eventId.toString());
    root.put("schema_version", 1);
    root.put("type", "transaction.posted");
    root.put("transaction_id", transactionId.toString());
    root.put("kind", kind);
    root.put("currency", "EUR");
    root.put("amount", amount);
    root.put("posted_at", POSTED_AT.format(at));
    root.put("key_account_id", keyAccount.toString());
    root.put("request_id", "request-" + eventId);
    if (reverses == null) {
      root.putNull("reverses_transaction_id");
    } else {
      root.put("reverses_transaction_id", reverses.toString());
    }
    ArrayNode lines = root.putArray("entries");
    for (EntrySpec entry : entries) {
      ObjectNode line = lines.addObject();
      line.put("account_id", entry.accountId().toString());
      line.put("account_kind", entry.accountKind());
      line.put("normal_side", entry.normalSide());
      line.put("side", entry.side());
      line.put("amount", entry.amount());
    }
    return new Published(keyAccount, eventId, transactionId, JSON.writeValueAsString(root));
  }

  private void publish(Published... events) throws Exception {
    publish(topic(), events);
  }

  private void publish(String topic, Published... events) throws Exception {
    Properties settings = new Properties();
    settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(settings)) {
      for (Published event : events) {
        ProducerRecord<String, String> record =
            new ProducerRecord<>(topic, event.keyAccount().toString(), event.value());
        record
            .headers()
            .add("event_id", event.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("request_id", requestId(event).getBytes(StandardCharsets.UTF_8));
        producer.send(record).get(10, TimeUnit.SECONDS);
      }
    }
  }

  private String topic() {
    return app().getEnvironment().getRequiredProperty("tally.monitor.kafka.topic");
  }

  private void awaitProcessed(List<Published> events) {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(processedCount(events)).isEqualTo(events.size()));
  }

  private long processedCount(List<Published> events) {
    List<UUID> ids = events.stream().map(Published::eventId).distinct().toList();
    String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
    String sql =
        "SELECT count(*) FROM monitor.processed_event WHERE event_id IN (" + placeholders + ")";
    try (Connection connection = appJdbc();
        PreparedStatement select = connection.prepareStatement(sql)) {
      for (int i = 0; i < ids.size(); i++) {
        select.setObject(i + 1, ids.get(i));
      }
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<MovementRow> movements(UUID transactionId) throws java.sql.SQLException {
    String sql =
        "SELECT direction, kind, amount, currency, counted, r2_candidate, event_time"
            + " FROM monitor.movement WHERE transaction_id = ? ORDER BY account_id";
    try (Connection connection = appJdbc();
        PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, transactionId);
      try (ResultSet rows = select.executeQuery()) {
        List<MovementRow> result = new ArrayList<>();
        while (rows.next()) {
          result.add(
              new MovementRow(
                  rows.getString("direction"),
                  rows.getString("kind"),
                  rows.getLong("amount"),
                  rows.getString("currency"),
                  rows.getBoolean("counted"),
                  rows.getBoolean("r2_candidate"),
                  rows.getObject("event_time", java.time.OffsetDateTime.class).toInstant()));
        }
        return result;
      }
    }
  }

  private long movementCount(UUID accountId) throws java.sql.SQLException {
    return count("SELECT count(*) FROM monitor.movement WHERE account_id = ?", accountId);
  }

  private long alertCount(UUID accountId) throws java.sql.SQLException {
    return count("SELECT count(*) FROM monitor.alert WHERE account_id = ?", accountId);
  }

  private long count(String sql, UUID accountId) throws java.sql.SQLException {
    try (Connection connection = appJdbc();
        PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, accountId);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getLong(1);
      }
    }
  }

  private List<AlertRow> alerts(UUID accountId) throws java.sql.SQLException {
    try (Connection connection = appJdbc();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT rule, trigger_key FROM monitor.alert WHERE account_id = ? ORDER BY id")) {
      select.setObject(1, accountId);
      try (ResultSet rows = select.executeQuery()) {
        List<AlertRow> result = new ArrayList<>();
        while (rows.next()) {
          result.add(new AlertRow(rows.getString("rule"), rows.getString("trigger_key")));
        }
        return result;
      }
    }
  }

  private List<CaseRow> cases(UUID accountId) throws java.sql.SQLException {
    try (Connection connection = appJdbc();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT rule, status, alert_count FROM monitor.case_file"
                    + " WHERE account_id = ? ORDER BY opened_at, id")) {
      select.setObject(1, accountId);
      try (ResultSet rows = select.executeQuery()) {
        List<CaseRow> result = new ArrayList<>();
        while (rows.next()) {
          result.add(
              new CaseRow(
                  rows.getString("rule"), rows.getString("status"), rows.getInt("alert_count")));
        }
        return result;
      }
    }
  }

  private List<String> caseEvents(UUID accountId) throws java.sql.SQLException {
    String sql =
        "SELECT event.type FROM monitor.case_event event"
            + " JOIN monitor.case_file case_file ON case_file.id = event.case_id"
            + " WHERE case_file.account_id = ? ORDER BY event.id";
    try (Connection connection = appJdbc();
        PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, accountId);
      try (ResultSet rows = select.executeQuery()) {
        List<String> result = new ArrayList<>();
        while (rows.next()) {
          result.add(rows.getString("type"));
        }
        return result;
      }
    }
  }

  private static String requestId(Published event) {
    return "request-" + event.eventId();
  }

  private record EntrySpec(
      UUID accountId, String accountKind, String normalSide, String side, long amount) {}

  private record Published(UUID keyAccount, UUID eventId, UUID transactionId, String value) {}

  private record MovementRow(
      String direction,
      String kind,
      long amount,
      String currency,
      boolean counted,
      boolean r2Candidate,
      Instant eventTime) {}

  private record AlertRow(String rule, String triggerKey) {}

  private record CaseRow(String rule, String status, int alertCount) {}
}
