package tally.monitor.ingest;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public record TransactionEvent(
    UUID eventId,
    int schemaVersion,
    String type,
    UUID transactionId,
    String kind,
    String currency,
    long amount,
    Instant postedAt,
    UUID keyAccountId,
    String requestId,
    UUID reversesTransactionId,
    List<Entry> entries) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public record Entry(
      UUID accountId, String accountKind, String normalSide, String side, long amount) {}

  public static Optional<TransactionEvent> parse(String json) {
    if (json == null) {
      return Optional.empty();
    }
    try {
      JsonNode root = JSON.readTree(json);
      requireObject(root);
      int schemaVersion = integer(required(root, "schema_version"));
      if (schemaVersion != 1) {
        return Optional.empty();
      }
      String type = text(required(root, "type"));
      String kind = text(required(root, "kind"));
      String currency = text(required(root, "currency"));
      long amount = longValue(required(root, "amount"));
      String postedAt = text(required(root, "posted_at"));
      String requestId = text(required(root, "request_id"));
      JsonNode reverses = required(root, "reverses_transaction_id");
      UUID reversesTransactionId = reverses.isNull() ? null : uuid(reverses);
      JsonNode eventEntries = required(root, "entries");
      if (!eventEntries.isArray()) {
        throw new IllegalArgumentException("expected array");
      }
      List<Entry> entries = new ArrayList<>();
      for (JsonNode entry : eventEntries) {
        requireObject(entry);
        entries.add(
            new Entry(
                uuid(required(entry, "account_id")),
                text(required(entry, "account_kind")),
                text(required(entry, "normal_side")),
                text(required(entry, "side")),
                longValue(required(entry, "amount"))));
      }
      return Optional.of(
          new TransactionEvent(
              uuid(required(root, "event_id")),
              schemaVersion,
              type,
              uuid(required(root, "transaction_id")),
              kind,
              currency,
              amount,
              Instant.parse(postedAt),
              uuid(required(root, "key_account_id")),
              requestId,
              reversesTransactionId,
              List.copyOf(entries)));
    } catch (JacksonException | DateTimeException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static JsonNode required(JsonNode object, String property) {
    JsonNode value = object.get(property);
    if (value == null) {
      throw new IllegalArgumentException("missing required property");
    }
    return value;
  }

  private static void requireObject(JsonNode value) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("expected object");
    }
  }

  private static String text(JsonNode value) {
    if (!value.isString()) {
      throw new IllegalArgumentException("expected string");
    }
    return value.stringValue();
  }

  private static int integer(JsonNode value) {
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("expected integer");
    }
    return value.intValue();
  }

  private static long longValue(JsonNode value) {
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("expected integral long");
    }
    return value.longValue();
  }

  private static UUID uuid(JsonNode value) {
    return UUID.fromString(text(value));
  }
}
