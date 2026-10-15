package tally.ledger.check;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class TopicCheck {

  public record Snapshot(Set<UUID> eventIds, long records) {}

  private TopicCheck() {}

  /** Reads only records before the end offsets observed at the start. */
  public static Snapshot read(String bootstrap, String topic, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    JsonMapper json = JsonMapper.builder().build();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrap,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic, remaining(deadline)).stream()
              .map(info -> new TopicPartition(topic, info.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, remaining(deadline));
      Set<UUID> eventIds = new HashSet<>();
      long records = 0;
      while (!reachedEnd(consumer, partitions, ends, deadline)) {
        Duration wait = remaining(deadline);
        if (wait.compareTo(Duration.ofMillis(100)) > 0) {
          wait = Duration.ofMillis(100);
        }
        for (ConsumerRecord<String, String> record : consumer.poll(wait)) {
          TopicPartition partition = new TopicPartition(topic, record.partition());
          if (record.offset() >= ends.get(partition)) {
            continue;
          }
          Header header = record.headers().lastHeader("event_id");
          String eventId =
              header == null
                  ? json.readTree(record.value()).get("event_id").asString()
                  : new String(header.value(), StandardCharsets.UTF_8);
          eventIds.add(UUID.fromString(eventId));
          records++;
        }
      }
      return new Snapshot(Set.copyOf(eventIds), records);
    } catch (KafkaException | JacksonException | IllegalArgumentException e) {
      throw new IllegalStateException("topic read failed: " + e.getMessage(), e);
    }
  }

  private static boolean reachedEnd(
      KafkaConsumer<String, String> consumer,
      List<TopicPartition> partitions,
      Map<TopicPartition, Long> ends,
      long deadline) {
    for (TopicPartition partition : partitions) {
      if (consumer.position(partition, remaining(deadline)) < ends.get(partition)) {
        return false;
      }
    }
    return true;
  }

  private static Duration remaining(long deadline) {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new IllegalStateException("topic read failed: timeout");
    }
    return Duration.ofNanos(nanos);
  }
}
