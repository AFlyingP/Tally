package tally.ledger.core;

import java.util.UUID;

public record AccountState(
    UUID id,
    AccountKind kind,
    String currency,
    Side normalSide,
    Long minBalance,
    AccountStatus status,
    long balance,
    long held,
    long version) {}
