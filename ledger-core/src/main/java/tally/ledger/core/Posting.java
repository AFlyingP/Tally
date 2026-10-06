package tally.ledger.core;

import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

public final class Posting {

  private final String currency;
  private final List<EntryLine> lines;
  private final long amount;
  private final SortedSet<UUID> accountIds;

  private Posting(String currency, List<EntryLine> lines, long amount, SortedSet<UUID> accountIds) {
    this.currency = currency;
    this.lines = lines;
    this.amount = amount;
    this.accountIds = accountIds;
  }

  public static Posting of(String currency, List<EntryLine> lines) {
    List<EntryLine> copiedLines = List.copyOf(lines);
    if (copiedLines.size() < 2) {
      throw new LedgerRuleException(
          RuleCode.UNBALANCED, "a posting must contain at least two lines");
    }

    long debits = 0;
    long credits = 0;
    SortedSet<UUID> accountIds = new TreeSet<>();
    for (EntryLine line : copiedLines) {
      if (line.side() == Side.DEBIT) {
        debits = Amounts.add(debits, line.amount());
      } else {
        credits = Amounts.add(credits, line.amount());
      }
      accountIds.add(line.accountId());
    }

    if (debits != credits) {
      throw new LedgerRuleException(RuleCode.UNBALANCED, "posting sides must balance");
    }
    if (currency == null || !currency.matches("^[A-Z]{3}$")) {
      throw new LedgerRuleException(
          RuleCode.INVALID_CURRENCY, "currency must be three uppercase letters");
    }

    return new Posting(
        currency, copiedLines, debits, Collections.unmodifiableSortedSet(accountIds));
  }

  public static Posting move(String currency, UUID debitAccount, UUID creditAccount, long amount) {
    if (debitAccount.equals(creditAccount)) {
      throw new LedgerRuleException(RuleCode.SAME_ACCOUNT, "accounts must be different");
    }
    return of(
        currency,
        List.of(
            new EntryLine(debitAccount, Side.DEBIT, amount),
            new EntryLine(creditAccount, Side.CREDIT, amount)));
  }

  public String currency() {
    return currency;
  }

  public List<EntryLine> lines() {
    return lines;
  }

  public long amount() {
    return amount;
  }

  public SortedSet<UUID> accountIds() {
    return accountIds;
  }

  public Posting reversed() {
    List<EntryLine> reversedLines =
        lines.stream()
            .map(line -> new EntryLine(line.accountId(), line.side().opposite(), line.amount()))
            .toList();
    return of(currency, reversedLines);
  }
}
