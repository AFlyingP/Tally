package tally.ledger.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class MoneyDto {

  private MoneyDto() {}

  public record Deposit(
      @NotNull UUID accountId,
      @NotNull @Min(1) Long amount,
      @NotNull String currency,
      @Size(max = 200) String description) {}

  public record Withdrawal(
      @NotNull UUID accountId,
      @NotNull @Min(1) Long amount,
      @NotNull String currency,
      @Size(max = 200) String description) {}

  public record Transfer(
      @NotNull UUID sourceAccountId,
      @NotNull UUID destinationAccountId,
      @NotNull @Min(1) Long amount,
      @NotNull String currency,
      @Size(max = 200) String description) {}

  public record Reversal(@Size(max = 200) String reason) {}
}
