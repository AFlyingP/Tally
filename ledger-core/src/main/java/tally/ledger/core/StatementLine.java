package tally.ledger.core;

import java.time.LocalDate;

public record StatementLine(
    int lineNumber,
    String reference,
    String creditorAccount,
    long amount,
    String currency,
    LocalDate valueDate) {}
