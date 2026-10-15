package tally.ledger.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuditDto {

  private AuditDto() {}

  public record AuditRow(
      UUID id,
      Instant occurredAt,
      String actor,
      String actorRole,
      String scopes,
      String action,
      String resourceType,
      String resourceId,
      String requestId,
      String outcome,
      String reason,
      UUID transactionId) {}

  public record AuditPage(List<AuditRow> items, String nextCursor) {}
}
