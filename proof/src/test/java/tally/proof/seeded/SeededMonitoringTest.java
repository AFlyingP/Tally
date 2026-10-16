package tally.proof.seeded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tally.proof.fault.FaultStack;
import tally.proof.fault.FaultStack.Part;
import tally.proof.harness.Seeds;
import tally.proof.harness.Sizes;
import tally.proof.seeded.TrafficGenerator.AlertKey;
import tally.proof.seeded.TrafficGenerator.Event;
import tally.proof.seeded.TrafficGenerator.Traffic;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("seeded")
public class SeededMonitoringTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void findsSeededViolationsAndIgnoresNearMisses() {
    Seeds.print("monitoring");
    Map<String, Object> result = measure(Sizes.current(), Seeds.seed());
    List<?> rules = (List<?>) result.get("rules");
    for (Object value : rules) {
      Map<?, ?> rule = (Map<?, ?>) value;
      System.out.println(
          "monitoring rule="
              + rule.get("rule")
              + " expected="
              + rule.get("expected")
              + " found="
              + rule.get("found")
              + " precision="
              + rule.get("precision")
              + " recall="
              + rule.get("recall"));
      assertThat(rule.get("precision")).isEqualTo(1.0);
      assertThat(rule.get("recall")).isEqualTo(1.0);
    }
    assertThat(rules).hasSize(3);
    System.out.println("monitoring double_delivery_diff=" + result.get("double_delivery_diff"));
    assertThat(result.get("double_delivery_diff")).isEqualTo(0);
  }

  @Test
  void generatedEventsMatchTheSchema() throws IOException {
    Seeds.print("monitoring");
    long seed = Seeds.seed();
    Path schemaPath =
        Path.of(
            System.getProperty("proof.root", ".."),
            "ledger/src/main/resources/events/transaction-posted.v1.schema.json");
    JsonNode schema = JSON.readTree(Files.readString(schemaPath));
    Set<String> required = names(schema.get("required"));
    Set<String> properties = propertyNames(schema.get("properties"));
    JsonNode entrySchema = schema.get("properties").get("entries").get("items");
    Set<String> entryRequired = names(entrySchema.get("required"));
    Set<String> kinds = names(schema.get("properties").get("kind").get("enum"));
    Map<String, JsonNode> eventsByKind = new LinkedHashMap<>();
    for (Event event : TrafficGenerator.generate(1, seed).events()) {
      JsonNode value = JSON.readTree(event.json());
      String kind = value.get("kind").asString();
      if (kind.equals("DEPOSIT") || kind.equals("TRANSFER")) {
        eventsByKind.putIfAbsent(kind, value);
      }
    }
    assertThat(eventsByKind.keySet()).containsExactlyInAnyOrder("DEPOSIT", "TRANSFER");
    for (JsonNode event : eventsByKind.values()) {
      assertThat(propertyNames(event)).isEqualTo(required);
      assertThat(propertyNames(event)).isEqualTo(properties);
      assertThat(event.get("schema_version").asInt()).isEqualTo(1);
      assertThat(event.get("type").asString()).isEqualTo("transaction.posted");
      assertThat(kinds).contains(event.get("kind").asString());
      for (JsonNode entry : event.get("entries")) {
        assertThat(propertyNames(entry)).isEqualTo(entryRequired);
      }
    }
  }

  public static Map<String, Object> measure(Sizes sizes, long seed) {
    try (FaultStack stack =
        FaultStack.start("pessimistic", Set.of(Part.POSTGRES, Part.KAFKA, Part.MONITOR))) {
      stack.createTopic();
      Traffic traffic = TrafficGenerator.generate(sizes.monitoringPerRule(), seed);
      publish(stack, traffic.events(), 1);
      awaitProcessedEvents(stack, traffic.events().size());
      Set<AlertKey> found = readAlerts(stack);

      stack.resetMonitor();
      publish(stack, traffic.events(), 2);
      awaitProcessedEvents(stack, traffic.events().size());
      Set<AlertKey> foundTwice = readAlerts(stack);

      List<Map<String, Object>> rules = new ArrayList<>();
      for (String rule : List.of("R1", "R2", "R3")) {
        int expected = traffic.expectedPerRule().get(rule);
        int foundCount = 0;
        int truePositive = 0;
        for (AlertKey alert : found) {
          if (alert.rule().equals(rule)) {
            foundCount++;
            if (traffic.expected().contains(alert)) {
              truePositive++;
            }
          }
        }
        int falsePositive = foundCount - truePositive;
        double precision =
            foundCount == 0 ? (expected == 0 ? 1.0 : 0.0) : truePositive / (double) foundCount;
        double recall = expected == 0 ? 1.0 : truePositive / (double) expected;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rule", rule);
        values.put("expected", expected);
        values.put("found", foundCount);
        values.put("true_positive", truePositive);
        values.put("false_positive", falsePositive);
        values.put("precision", precision);
        values.put("recall", recall);
        rules.add(values);
      }
      int difference = symmetricDifferenceSize(found, foundTwice);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("rules", List.copyOf(rules));
      result.put("double_delivery_diff", difference);
      return result;
    }
  }

  private static void publish(FaultStack stack, List<Event> events, int deliveries) {
    Properties properties = new Properties();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafkaBootstrap());
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.ACKS_CONFIG, "all");
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
      for (Event event : events) {
        for (int delivery = 0; delivery < deliveries; delivery++) {
          ProducerRecord<String, String> record =
              new ProducerRecord<>(FaultStack.MONITOR_TOPIC, event.key(), event.json());
          byte[] eventId = event.eventId().toString().getBytes(StandardCharsets.UTF_8);
          record.headers().add("event_id", eventId);
          record.headers().add("request_id", eventId);
          producer.send(record);
        }
      }
      producer.flush();
    }
  }

  private static void awaitProcessedEvents(FaultStack stack, int expected) {
    try (Connection connection = stack.monitorJdbc()) {
      await()
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(500))
          .atMost(Duration.ofSeconds(120))
          .until(() -> processedEventCount(connection) == expected);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to query processed events", e);
    }
  }

  private static long processedEventCount(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT count(*) FROM monitor.processed_event")) {
      result.next();
      return result.getLong(1);
    }
  }

  private static Set<AlertKey> readAlerts(FaultStack stack) {
    Set<AlertKey> alerts = new HashSet<>();
    try (Connection connection = stack.monitorJdbc();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery("SELECT rule, account_id, trigger_key FROM monitor.alert")) {
      while (result.next()) {
        alerts.add(
            new AlertKey(
                result.getString("rule"),
                UUID.fromString(result.getString("account_id")),
                result.getString("trigger_key")));
      }
      return alerts;
    } catch (SQLException e) {
      throw new IllegalStateException("failed to query monitor alerts", e);
    }
  }

  private static int symmetricDifferenceSize(Set<AlertKey> first, Set<AlertKey> second) {
    int difference = 0;
    for (AlertKey key : first) {
      if (!second.contains(key)) {
        difference++;
      }
    }
    for (AlertKey key : second) {
      if (!first.contains(key)) {
        difference++;
      }
    }
    return difference;
  }

  private static Set<String> names(JsonNode values) {
    Set<String> names = new HashSet<>();
    for (JsonNode value : values) {
      names.add(value.asString());
    }
    return names;
  }

  private static Set<String> propertyNames(JsonNode value) {
    Set<String> names = new HashSet<>();
    names.addAll(value.propertyNames());
    return names;
  }
}
