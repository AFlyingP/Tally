package tally.ledger.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import tally.ledger.core.HoldState;

public final class HoldDto {

  private HoldDto() {}

  public record Place(
      @NotNull UUID accountId,
      @NotNull @Min(1) Long amount,
      @NotNull String currency,
      @NotNull Instant expiresAt) {}

  public record Capture(@NotNull @Min(1) Long amount, @NotNull UUID destinationAccountId) {}

  public record Hold(
      UUID id,
      UUID accountId,
      String currency,
      long amount,
      HoldState state,
      Instant expiresAt,
      Long capturedAmount,
      UUID captureTransactionId,
      Instant createdAt) {}
}
