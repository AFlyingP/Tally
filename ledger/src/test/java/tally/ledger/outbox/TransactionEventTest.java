package tally.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tally.ledger.core.AccountKind;
import tally.ledger.core.AccountState;
import tally.ledger.core.AccountStatus;
import tally.ledger.core.Side;
import tally.ledger.core.TransactionKind;
import tally.ledger.posting.PostedTransaction;
import tally.ledger.posting.PostedTransaction.PostedEntry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TransactionEventTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID SOURCE = new UUID(0, 2);
  private static final UUID DESTINATION = new UUID(0, 1);
  private static final UUID TRANSACTION_ID = new UUID(0, 3);
  private static final UUID AUDIT_ID = new UUID(0, 4);
  private static final UUID EVENT_ID = new UUID(0, 5);
  private static final String REQUEST_ID = "transfer-request";
  private static final Instant POSTED_AT = Instant.parse("2026-01-02T03:04:05.123456Z");

  @Test
  void jsonHasExactlyTheSchemaPropertiesInOrder() throws IOException {
    JsonNode schema;
    try (InputStream input =
        getClass().getResourceAsStream("/events/transaction-posted.v1.schema.json")) {
      assertThat(input).isNotNull();
      schema = JSON.readTree(input);
    }
    JsonNode event = JSON.readTree(event(POSTED_AT).toJson());

    assertThat(event.propertyNames()).containsExactlyElementsOf(strings(schema.get("required")));
    JsonNode entries = event.get("entries");
    assertThat(entries.size()).isEqualTo(2);
    List<String> entryProperties =
        strings(schema.get("properties").get("entries").get("items").get("required"));
    for (JsonNode entry : entries) {
      assertThat(entry.propertyNames()).containsExactlyElementsOf(entryProperties);
    }
    assertThat(event.get("event_id").asString()).isEqualTo(EVENT_ID.toString());
    assertThat(event.get("schema_version").intValue()).isEqualTo(1);
    assertThat(event.get("type").asString()).isEqualTo("transaction.posted");
    assertThat(event.get("transaction_id").asString()).isEqualTo(TRANSACTION_ID.toString());
    assertThat(event.get("kind").asString()).isEqualTo("TRANSFER");
    assertThat(event.get("currency").asString()).isEqualTo("EUR");
    assertThat(event.get("amount").isIntegralNumber()).isTrue();
    assertThat(event.get("amount").longValue()).isEqualTo(500);
    assertThat(event.get("request_id").asString()).isEqualTo(REQUEST_ID);
    assertThat(event.get("reverses_transaction_id").isNull()).isTrue();
    assertThat(entries.get(0).get("account_id").asString()).isEqualTo(SOURCE.toString());
    assertThat(entries.get(1).get("account_id").asString()).isEqualTo(DESTINATION.toString());
    for (JsonNode entry : entries) {
      assertThat(entry.get("account_kind").asString()).isEqualTo("CUSTOMER");
      assertThat(entry.get("normal_side").asString()).isEqualTo("CREDIT");
      assertThat(entry.get("amount").isIntegralNumber()).isTrue();
      assertThat(entry.get("amount").longValue()).isEqualTo(500);
    }
    assertThat(entries.get(0).get("side").asString()).isEqualTo("DEBIT");
    assertThat(entries.get(1).get("side").asString()).isEqualTo("CREDIT");
  }

  @Test
  void keyAccountIsFirstLineAccount() {
    TransactionEvent event = event(POSTED_AT);

    assertThat(event.keyAccountId()).isEqualTo(SOURCE);
    assertThat(JSON.readTree(event.toJson()).get("key_account_id").asString())
        .isEqualTo(SOURCE.toString());
  }

  @Test
  void postedAtHasSixFractionDigits() {
    for (String timestamp :
        List.of(
            "2026-01-02T03:04:05Z", "2026-01-02T03:04:05.123Z", "2026-01-02T03:04:05.123456789Z")) {
      String postedAt =
          JSON.readTree(event(Instant.parse(timestamp)).toJson()).get("posted_at").asString();

      assertThat(postedAt).matches("2026-01-02T03:04:05\\.\\d{6}Z");
      assertThat(Instant.parse(postedAt))
          .isEqualTo(Instant.parse(timestamp).truncatedTo(ChronoUnit.MICROS));
    }
  }

  private static TransactionEvent event(Instant postedAt) {
    PostedTransaction tx =
        new PostedTransaction(
            TRANSACTION_ID,
            AUDIT_ID,
            TransactionKind.TRANSFER,
            "EUR",
            500,
            postedAt,
            "transfer",
            null,
            List.of(
                new PostedEntry(10, SOURCE, Side.DEBIT, 500),
                new PostedEntry(11, DESTINATION, Side.CREDIT, 500)));
    Map<UUID, AccountState> accounts =
        Map.of(
            SOURCE,
            new AccountState(
                SOURCE,
                AccountKind.CUSTOMER,
                "EUR",
                Side.CREDIT,
                0L,
                AccountStatus.OPEN,
                500,
                0,
                0),
            DESTINATION,
            new AccountState(
                DESTINATION,
                AccountKind.CUSTOMER,
                "EUR",
                Side.CREDIT,
                0L,
                AccountStatus.OPEN,
                0,
                0,
                0));
    return TransactionEvent.of(tx, EVENT_ID, REQUEST_ID, accounts);
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : array) {
      values.add(value.asString());
    }
    return values;
  }
}
