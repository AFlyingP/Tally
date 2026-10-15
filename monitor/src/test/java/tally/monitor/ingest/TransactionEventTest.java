package tally.monitor.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class TransactionEventTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID EVENT_ID = UUID.fromString("0190a5c0-0000-7000-8000-000000000001");
  private static final UUID TRANSACTION_ID =
      UUID.fromString("0190a5c0-0000-7000-8000-000000000002");
  private static final UUID ACCOUNT_ID = UUID.fromString("0190a5c0-0000-7000-8000-000000000003");

  @Test
  void validEventParses() {
    Optional<TransactionEvent> parsed = TransactionEvent.parse(JSON.writeValueAsString(event()));

    assertThat(parsed).isPresent();
    assertThat(parsed.orElseThrow().eventId()).isEqualTo(EVENT_ID);
    assertThat(parsed.orElseThrow().transactionId()).isEqualTo(TRANSACTION_ID);
    assertThat(parsed.orElseThrow().entries()).hasSize(2);
  }

  @Test
  void extraPropertiesAtBothLevelsAreAccepted() {
    ObjectNode event = event();
    event.put("future_property", "value");
    event.put("type", "future.event");
    event.put("kind", "FUTURE_KIND");
    event.put("currency", "x");
    event.put("amount", 0);
    ArrayNode entries = (ArrayNode) event.get("entries");
    ObjectNode firstEntry = (ObjectNode) entries.get(0);
    firstEntry.put("future_entry_property", true);
    firstEntry.put("account_kind", "FUTURE_ACCOUNT");
    firstEntry.put("normal_side", "SIDEWAYS");
    firstEntry.put("side", "OTHER");
    firstEntry.put("amount", 0);

    Optional<TransactionEvent> parsed = TransactionEvent.parse(JSON.writeValueAsString(event));

    assertThat(parsed).isPresent();
    assertThat(parsed.orElseThrow().kind()).isEqualTo("FUTURE_KIND");
    assertThat(parsed.orElseThrow().entries().getFirst().amount()).isZero();
  }

  @Test
  void missingRequiredPropertyGivesEmpty() {
    ObjectNode event = event();
    event.remove("request_id");

    assertThat(TransactionEvent.parse(JSON.writeValueAsString(event))).isEmpty();
  }

  @Test
  void schemaVersionTwoGivesEmpty() {
    ObjectNode event = event();
    event.put("schema_version", 2);

    assertThat(TransactionEvent.parse(JSON.writeValueAsString(event))).isEmpty();
  }

  @Test
  void invalidJsonGivesEmpty() {
    assertThat(TransactionEvent.parse("not json")).isEmpty();
  }

  private static ObjectNode event() {
    ObjectNode event = JSON.createObjectNode();
    event.put("event_id", EVENT_ID.toString());
    event.put("schema_version", 1);
    event.put("type", "transaction.posted");
    event.put("transaction_id", TRANSACTION_ID.toString());
    event.put("kind", "TRANSFER");
    event.put("currency", "EUR");
    event.put("amount", 100);
    event.put("posted_at", "2026-03-10T00:00:00Z");
    event.put("key_account_id", ACCOUNT_ID.toString());
    event.put("request_id", "request-1");
    event.putNull("reverses_transaction_id");
    ArrayNode entries = event.putArray("entries");
    entries.add(entry("DEBIT"));
    entries.add(entry("CREDIT"));
    return event;
  }

  private static ObjectNode entry(String side) {
    ObjectNode entry = JSON.createObjectNode();
    entry.put("account_id", ACCOUNT_ID.toString());
    entry.put("account_kind", "CUSTOMER");
    entry.put("normal_side", "CREDIT");
    entry.put("side", side);
    entry.put("amount", 100);
    return entry;
  }
}
