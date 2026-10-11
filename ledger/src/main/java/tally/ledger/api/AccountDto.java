package tally.ledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tally.ledger.core.AccountKind;
import tally.ledger.core.AccountStatus;
import tally.ledger.core.Side;

public final class AccountDto {

  private AccountDto() {}

  public record CreateAccount(String currency, Side normalSide, Long minBalance) {}

  public record Account(
      UUID id,
      AccountKind kind,
      String currency,
      Side normalSide,
      Long minBalance,
      AccountStatus status,
      Instant createdAt) {}

  public record Balance(
      UUID accountId, String currency, long balance, Long held, Long available, Instant asOf) {}

  public record Entry(long id, UUID transactionId, Side side, long amount, Instant postedAt) {}

  public record EntryPage(List<Entry> items, String nextCursor) {}
}
