package tally.ledger.outbox;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tally.ledger.core.AccountKind;
import tally.ledger.core.AccountState;
import tally.ledger.core.Side;
import tally.ledger.core.TransactionKind;
import tally.ledger.posting.PostedTransaction;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public record TransactionEvent(
    UUID eventId,
    int schemaVersion,
    String type,
    UUID transactionId,
    TransactionKind kind,
    String currency,
    long amount,
    Instant postedAt,
    UUID keyAccountId,
    String requestId,
    UUID reversesTransactionId,
    List<Entry> entries) {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter POSTED_AT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

  public record Entry(
      UUID accountId, AccountKind accountKind, Side normalSide, Side side, long amount) {}

  public static TransactionEvent of(
      PostedTransaction tx, UUID eventId, String requestId, Map<UUID, AccountState> accounts) {
    List<Entry> entries =
        tx.entries().stream()
            .map(
                line -> {
                  AccountState account = accounts.get(line.accountId());
                  return new Entry(
                      line.accountId(),
                      account.kind(),
                      account.normalSide(),
                      line.side(),
                      line.amount());
                })
            .toList();
    return new TransactionEvent(
        eventId,
        1,
        "transaction.posted",
        tx.id(),
        tx.kind(),
        tx.currency(),
        tx.amount(),
        tx.postedAt(),
        tx.entries().getFirst().accountId(),
        requestId,
        tx.reversesId(),
        entries);
  }

  /** Serializes the event in schema order, with a UTC timestamp at microsecond precision. */
  public String toJson() {
    ObjectNode event = JSON.createObjectNode();
    event.put("event_id", eventId.toString());
    event.put("schema_version", schemaVersion);
    event.put("type", type);
    event.put("transaction_id", transactionId.toString());
    event.put("kind", kind.name());
    event.put("currency", currency);
    event.put("amount", amount);
    event.put("posted_at", POSTED_AT.format(postedAt));
    event.put("key_account_id", keyAccountId.toString());
    event.put("request_id", requestId);
    event.put(
        "reverses_transaction_id",
        reversesTransactionId == null ? null : reversesTransactionId.toString());
    ArrayNode lines = event.putArray("entries");
    for (Entry entry : entries) {
      ObjectNode line = lines.addObject();
      line.put("account_id", entry.accountId().toString());
      line.put("account_kind", entry.accountKind().name());
      line.put("normal_side", entry.normalSide().name());
      line.put("side", entry.side().name());
      line.put("amount", entry.amount());
    }
    return JSON.writeValueAsString(event);
  }
}
