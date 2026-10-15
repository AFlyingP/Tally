package tally.extbank;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;

public final class ExtbankDto {

  private ExtbankDto() {}

  public record Submit(
      @NotNull @Pattern(regexp = "^[A-Z0-9-]{8,40}$") String reference,
      @NotNull @Pattern(regexp = "^[A-Z0-9-]{4,34}$") String creditorAccount,
      @NotNull @Positive Long amount,
      @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

  public record BankPayment(
      String reference,
      String creditorAccount,
      long amount,
      String currency,
      String status,
      String rejectReason,
      LocalDate valueDate,
      int confirmations) {}

  public record RuleRequest(
      @Pattern(regexp = "^[A-Z0-9-]{4,34}$") String creditorAccount,
      @NotNull Behaviour behaviour,
      @Min(0) @Max(60000) Integer delayMs,
      @Min(1) Integer remaining) {

    public RuleRequest {
      delayMs = delayMs == null ? 0 : delayMs;
    }
  }

  public record Rule(
      long id, String creditorAccount, Behaviour behaviour, int delayMs, Integer remaining) {}

  public record RuleList(List<Rule> items) {}
}
