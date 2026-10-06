package tally.ledger.audit;

import java.util.UUID;
import tally.platform.security.Caller;

/** The values of one audit row that the acting code decides. */
public record AuditEntry(
    String actor,
    String actorRole,
    String scopes,
    String action,
    String resourceType,
    String resourceId,
    String requestId,
    String outcome,
    String reason,
    UUID transactionId) {

  public static AuditEntry success(
      Caller caller, String action, String resourceType, String resourceId, String requestId) {
    return new AuditEntry(
        caller.clientId(),
        caller.role(),
        String.join(" ", caller.scopes().stream().sorted().toList()),
        action,
        resourceType,
        resourceId,
        requestId,
        "SUCCESS",
        null,
        null);
  }

  public AuditEntry withTransaction(UUID id) {
    return new AuditEntry(
        actor,
        actorRole,
        scopes,
        action,
        resourceType,
        "transaction".equals(resourceType) ? id.toString() : resourceId,
        requestId,
        outcome,
        reason,
        id);
  }
}
