package tally.ledger.api;

import java.time.Instant;
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
}
