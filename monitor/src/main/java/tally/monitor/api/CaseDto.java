package tally.monitor.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CaseDto {

  private CaseDto() {}

  public record Case(
      UUID id,
      UUID accountId,
      String rule,
      String status,
      String assignee,
      int alertCount,
      Instant openedAt,
      Instant updatedAt,
      Instant closedAt) {}

  public record CasePage(List<Case> items, String nextCursor) {}

  public record CaseDetail(
      UUID id,
      UUID accountId,
      String rule,
      String status,
      String assignee,
      int alertCount,
      Instant openedAt,
      Instant updatedAt,
      Instant closedAt,
      List<Alert> alerts,
      List<Event> events) {}

  public record Alert(long id, String triggerKey, Instant alertTime, String detail) {}

  public record Event(String type, String actor, String detail, Instant occurredAt) {}

  public record Assign(@NotNull @NotBlank @Size(min = 1, max = 64) String assignee) {}

  public record Note(@NotNull @NotBlank @Size(min = 1, max = 2000) String text) {}

  public record Close(@NotNull String resolution, @Size(max = 2000) String note) {}
}
