package tally.ledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tally.ledger.core.Side;
import tally.ledger.core.TransactionKind;
import tally.ledger.posting.PostedTransaction;

public final class TransactionDto {

  private TransactionDto() {}

  public record Transaction(
      UUID id,
      TransactionKind kind,
      String currency,
      long amount,
      String description,
      Instant postedAt,
      UUID reversesTransactionId,
      UUID reversedByTransactionId,
      List<Entry> entries) {

    public static Transaction from(PostedTransaction tx) {
      return new Transaction(
          tx.id(),
          tx.kind(),
          tx.currency(),
          tx.amount(),
          tx.description(),
          tx.postedAt(),
          tx.reversesId(),
          null,
          tx.entries().stream()
              .map(e -> new Entry(e.id(), e.accountId(), e.side(), e.amount()))
              .toList());
    }
  }

  public record Entry(long id, UUID accountId, Side side, long amount) {}
}
