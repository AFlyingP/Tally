package tally.monitor.ingest;

import java.time.Instant;
import java.util.UUID;

public record Movement(
    UUID transactionId,
    UUID accountId,
    String direction,
    String kind,
    long amount,
    String currency,
    boolean counted,
    boolean r2Candidate,
    Instant eventTime) {}
