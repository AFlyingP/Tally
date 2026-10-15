package tally.ledger.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PaymentDto {

  private PaymentDto() {}

  public record Create(
      @NotNull UUID sourceAccountId,
      @NotNull @Min(1) Long amount,
      @NotNull String currency,
      @NotNull @Pattern(regexp = "^[A-Z0-9-]{4,34}$") String creditorAccount) {}

  public record Payment(
      UUID id,
      UUID sourceAccountId,
      String currency,
      long amount,
      String creditorAccount,
      String externalReference,
      String state,
      LocalDate valueDate,
      String reason,
      int statusAttempts,
      UUID captureTransactionId,
      UUID resolutionTransactionId,
      Instant createdAt,
      Instant updatedAt,
      List<Event> events) {}

  public record Event(String fromState, String toState, String detail, Instant occurredAt) {}
}
