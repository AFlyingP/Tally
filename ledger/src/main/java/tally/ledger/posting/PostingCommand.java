package tally.ledger.posting;

import java.util.Map;
import java.util.UUID;
import tally.ledger.audit.AuditEntry;
import tally.ledger.core.Posting;
import tally.ledger.core.TransactionKind;
import tally.platform.security.Caller;

public record PostingCommand(
    TransactionKind kind,
    Posting posting,
    long keyId,
    UUID reversesId,
    String description,
    Map<UUID, Long> heldDelta,
    AuditEntry audit,
    Caller caller,
    String requestId) {}
