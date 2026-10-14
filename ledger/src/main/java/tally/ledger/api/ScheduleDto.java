package tally.ledger.api;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tally.ledger.core.Frequency;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class ScheduleDto {

  private ScheduleDto() {}

  public record Create(
      UUID sourceAccountId,
      UUID destinationAccountId,
      Long amount,
      String currency,
      @JsonDeserialize(using = FrequencyDeserializer.class) Frequency frequency,
      Instant startAt,
      Integer dayOfMonth,
      LocalDate endDate,
      @Size(max = 200) String description) {}

  public record Schedule(
      UUID id,
      UUID sourceAccountId,
      UUID destinationAccountId,
      String currency,
      long amount,
      Frequency frequency,
      Instant startAt,
      Integer dayOfMonth,
      LocalDate endDate,
      String status,
      String description,
      Instant createdAt) {}

  public record Occurrence(
      UUID id,
      LocalDate occurrenceDate,
      Instant dueAt,
      String state,
      int attempts,
      Instant nextAttemptAt,
      UUID transactionId,
      String reason) {}

  public record OccurrenceList(List<Occurrence> items) {}

  public static final class FrequencyDeserializer extends ValueDeserializer<Frequency> {

    @Override
    public Frequency deserialize(JsonParser parser, DeserializationContext context)
        throws JacksonException {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        return (Frequency) context.handleUnexpectedToken(Frequency.class, parser);
      }
      try {
        return Frequency.valueOf(parser.getString());
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
  }
}
