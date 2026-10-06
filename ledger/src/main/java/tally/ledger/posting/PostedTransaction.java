package tally.ledger.posting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tally.ledger.core.Side;
import tally.ledger.core.TransactionKind;

public record PostedTransaction(
    UUID id,
    UUID auditId,
    TransactionKind kind,
    String currency,
    long amount,
    Instant postedAt,
    String description,
    UUID reversesId,
    List<PostedEntry> entries) {

  public record PostedEntry(long id, UUID accountId, Side side, long amount) {}
}
