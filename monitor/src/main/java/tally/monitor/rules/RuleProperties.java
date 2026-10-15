package tally.monitor.rules;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tally.monitor")
public record RuleProperties(Kafka kafka, R1 r1, R2 r2, R3 r3, long caseJoinSeconds) {

  public record Kafka(String bootstrap, String topic, String group) {

    public Kafka {
      if (bootstrap == null || !bootstrap.matches("[^:\\s]+:\\d+")) {
        throw invalid("tally.monitor.kafka.bootstrap", "must be host:port");
      }
      if (topic == null || topic.isBlank()) {
        throw invalid("tally.monitor.kafka.topic", "must not be empty");
      }
      if (group == null || group.isBlank()) {
        throw invalid("tally.monitor.kafka.group", "must not be empty");
      }
    }
  }

  public record R1(int maxCount, long windowSeconds) {

    public R1 {
      if (maxCount < 1 || maxCount > 10000) {
        throw invalid("tally.monitor.r1.max-count", "must be between 1 and 10000");
      }
      if (windowSeconds < 1 || windowSeconds > 604800) {
        throw invalid("tally.monitor.r1.window-seconds", "must be between 1 and 604800");
      }
    }
  }

  public record R2(int minCount, long windowSeconds, long threshold, int lowerPercent) {

    public R2 {
      if (minCount < 2 || minCount > 1000) {
        throw invalid("tally.monitor.r2.min-count", "must be between 2 and 1000");
      }
      if (windowSeconds < 1 || windowSeconds > 604800) {
        throw invalid("tally.monitor.r2.window-seconds", "must be between 1 and 604800");
      }
      if (threshold < 100 || threshold > 4000000000000000000L) {
        throw invalid("tally.monitor.r2.threshold", "must be between 100 and 4000000000000000000");
      }
      if (lowerPercent < 1 || lowerPercent > 99) {
        throw invalid("tally.monitor.r2.lower-percent", "must be between 1 and 99");
      }
    }
  }

  public record R3(int percent, long windowSeconds) {

    public R3 {
      if (percent < 1 || percent > 100) {
        throw invalid("tally.monitor.r3.percent", "must be between 1 and 100");
      }
      if (windowSeconds < 1 || windowSeconds > 604800) {
        throw invalid("tally.monitor.r3.window-seconds", "must be between 1 and 604800");
      }
    }
  }

  public RuleProperties {
    if (kafka == null) {
      throw invalid("tally.monitor.kafka.bootstrap", "must be configured");
    }
    if (r1 == null) {
      throw invalid("tally.monitor.r1.max-count", "must be configured");
    }
    if (r2 == null) {
      throw invalid("tally.monitor.r2.min-count", "must be configured");
    }
    if (r3 == null) {
      throw invalid("tally.monitor.r3.percent", "must be configured");
    }
    if (caseJoinSeconds < 1 || caseJoinSeconds > 604800) {
      throw invalid("tally.monitor.case-join-seconds", "must be between 1 and 604800");
    }
  }

  private static IllegalArgumentException invalid(String key, String reason) {
    return new IllegalArgumentException("invalid configuration: " + key + ": " + reason);
  }
}
