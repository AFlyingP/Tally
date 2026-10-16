package tally.proof.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import tally.proof.harness.Sizes;
import tally.proof.seeded.TrafficGenerator;
import tally.proof.seeded.TrafficGenerator.AlertKey;
import tally.proof.seeded.TrafficGenerator.Event;
import tally.proof.seeded.TrafficGenerator.Traffic;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class MonitorScenario {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

  private MonitorScenario() {}

  public static FaultResult monitorKill(FaultStack stack, String strategy, Sizes sizes, long seed) {
    stack.resetMonitor();
    stack.createTopic();
    Traffic traffic = TrafficGenerator.generate(sizes.monitoringPerRule(), seed);
    List<Event> events = traffic.events();
    Set<UUID> accounts = generatedCustomerAccounts(events);
    int half = events.size() / 2;
    int quarter = (events.size() + 3) / 4;

    publish(stack, events.subList(0, half));
    awaitProcessedEvents(stack, events, quarter);
    stack.kill("monitor");
    publish(stack, events.subList(half, events.size()));
    stack.start("monitor");
    long processed = awaitProcessedEvents(stack, events, events.size());

    Map<String, Integer> counts = readAlertCounts(stack, traffic.expected(), accounts);
    assertThat(counts.get("missing_alerts")).as("missing monitor alerts").isZero();
    assertThat(counts.get("extra_alerts")).as("extra monitor alerts").isZero();
    assertThat(counts.get("duplicate_alerts")).as("duplicate monitor alerts").isZero();

    return new FaultResult(
        Scenario.MONITOR_KILL,
        strategy,
        events.size(),
        Math.toIntExact(processed),
        0,
        0,
        0,
        0,
        List.of(),
        0,
        null,
        null,
        null,
        null,
        null,
        counts);
  }

  private static void publish(FaultStack stack, List<Event> events) {
    Properties properties = new Properties();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafkaBootstrap());
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.ACKS_CONFIG, "all");
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
      for (Event event : events) {
        ProducerRecord<String, String> record =
            new ProducerRecord<>(FaultStack.MONITOR_TOPIC, event.key(), event.json());
        byte[] eventId = event.eventId().toString().getBytes(StandardCharsets.UTF_8);
        record.headers().add("event_id", eventId);
        record.headers().add("request_id", eventId);
        producer.send(record);
      }
      producer.flush();
    }
  }

  private static long awaitProcessedEvents(FaultStack stack, List<Event> events, int expected) {
    String sql = "SELECT count(*) FROM monitor.processed_event WHERE event_id = ANY(?)";
    try (Connection connection = stack.monitorJdbc();
        PreparedStatement query = connection.prepareStatement(sql)) {
      Array eventIds =
          connection.createArrayOf(
              "uuid", events.stream().map(Event::eventId).toArray(UUID[]::new));
      try {
        query.setArray(1, eventIds);
        await()
            .pollDelay(Duration.ZERO)
            .pollInterval(POLL_INTERVAL)
            .atMost(Duration.ofSeconds(120))
            .until(() -> processedEventCount(query) >= expected);
        return processedEventCount(query);
      } finally {
        eventIds.free();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to query processed monitor events", e);
    }
  }

  private static long processedEventCount(PreparedStatement query) throws SQLException {
    try (ResultSet rows = query.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static Map<String, Integer> readAlertCounts(
      FaultStack stack, Set<AlertKey> expected, Set<UUID> accounts) {
    String alertsSql =
        "SELECT rule, account_id, trigger_key FROM monitor.alert WHERE account_id = ANY(?)";
    String duplicatesSql =
        "SELECT rule, account_id, trigger_key, count(*) FROM monitor.alert"
            + " WHERE account_id = ANY(?) GROUP BY 1, 2, 3 HAVING count(*) > 1";
    try (Connection connection = stack.monitorJdbc();
        PreparedStatement alertsQuery = connection.prepareStatement(alertsSql);
        PreparedStatement duplicatesQuery = connection.prepareStatement(duplicatesSql)) {
      Array accountIds = connection.createArrayOf("uuid", accounts.toArray(UUID[]::new));
      try {
        alertsQuery.setArray(1, accountIds);
        Set<AlertKey> found = new HashSet<>();
        try (ResultSet rows = alertsQuery.executeQuery()) {
          while (rows.next()) {
            found.add(
                new AlertKey(
                    rows.getString("rule"),
                    UUID.fromString(rows.getString("account_id")),
                    rows.getString("trigger_key")));
          }
        }

        int missing = 0;
        for (AlertKey alert : expected) {
          if (!found.contains(alert)) {
            missing++;
          }
        }
        int extra = 0;
        for (AlertKey alert : found) {
          if (!expected.contains(alert)) {
            extra++;
          }
        }

        duplicatesQuery.setArray(1, accountIds);
        int duplicates = 0;
        try (ResultSet rows = duplicatesQuery.executeQuery()) {
          while (rows.next()) {
            duplicates++;
          }
        }
        return Map.of(
            "missing_alerts", missing,
            "extra_alerts", extra,
            "duplicate_alerts", duplicates);
      } finally {
        accountIds.free();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to query monitor alerts", e);
    }
  }

  private static Set<UUID> generatedCustomerAccounts(List<Event> events) {
    Set<UUID> accounts = new HashSet<>();
    for (Event event : events) {
      JsonNode value = JSON.readTree(event.json());
      for (JsonNode entry : value.get("entries")) {
        if (entry.get("account_kind").asString().equals("CUSTOMER")) {
          accounts.add(UUID.fromString(entry.get("account_id").asString()));
        }
      }
    }
    return accounts;
  }
}
