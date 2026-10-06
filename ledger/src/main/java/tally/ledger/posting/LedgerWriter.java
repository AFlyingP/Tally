package tally.ledger.posting;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.ledger.audit.AuditWriter;
import tally.ledger.core.AccountState;
import tally.ledger.core.Amounts;
import tally.ledger.core.Balances;
import tally.ledger.core.EntryLine;
import tally.ledger.core.LedgerRuleException;
import tally.ledger.core.Posting;
import tally.ledger.core.RuleCode;
import tally.ledger.outbox.OutboxStore;
import tally.ledger.outbox.TransactionEvent;
import tally.ledger.posting.PostedTransaction.PostedEntry;
import tally.ledger.store.TransactionStore;
import tally.platform.id.UuidV7;

@Component
public class LedgerWriter {

  private final Clock clock;
  private final TransactionStore transactions;
  private final OutboxStore outbox;
  private final AuditWriter audit;

  public LedgerWriter(
      Clock clock, TransactionStore transactions, OutboxStore outbox, AuditWriter audit) {
    this.clock = clock;
    this.transactions = transactions;
    this.outbox = outbox;
    this.audit = audit;
  }

  /** Validates and writes a posting on the caller's database transaction. */
  public PostedTransaction post(LedgerTx tx, PostingCommand cmd) throws SQLException {
    Posting posting = cmd.posting();
    SortedSet<UUID> ids = posting.accountIds();
    Map<UUID, AccountState> accounts = tx.accounts(ids);
    for (UUID id : ids) {
      if (!accounts.containsKey(id)) {
        throw new LedgerRuleException(RuleCode.ACCOUNT_NOT_FOUND, "account not found");
      }
    }
    for (UUID id : ids) {
      Balances.requirePostable(accounts.get(id), cmd.kind(), posting.currency());
    }

    Map<UUID, Long> balances = new HashMap<>();
    Map<UUID, Long> held = new HashMap<>();
    for (UUID id : ids) {
      AccountState account = accounts.get(id);
      AccountState current = account;
      for (EntryLine line : posting.lines()) {
        if (line.accountId().equals(id)) {
          long balance = Balances.apply(current, line.side(), line.amount());
          current =
              new AccountState(
                  id,
                  account.kind(),
                  account.currency(),
                  account.normalSide(),
                  account.minBalance(),
                  account.status(),
                  balance,
                  account.held(),
                  account.version());
        }
      }
      long newHeld = Amounts.add(account.held(), cmd.heldDelta().getOrDefault(id, 0L));
      if (newHeld < 0) {
        throw new IllegalStateException("held balance is negative");
      }
      balances.put(id, current.balance());
      held.put(id, newHeld);
    }
    for (UUID id : ids) {
      Balances.requireWithinLimit(accounts.get(id), balances.get(id), held.get(id));
    }
    for (UUID id : ids) {
      tx.write(accounts.get(id), balances.get(id), held.get(id));
    }

    Connection connection = tx.connection();
    UUID id = UuidV7.next(clock);
    Instant postedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    transactions.insert(
        connection,
        id,
        cmd.kind(),
        posting.currency(),
        posting.amount(),
        cmd.keyId(),
        cmd.reversesId(),
        cmd.description(),
        cmd.caller().clientId(),
        cmd.requestId(),
        postedAt);
    List<Long> entryIds =
        transactions.insertEntries(connection, id, posting.currency(), posting.lines(), postedAt);
    List<PostedEntry> entries = new ArrayList<>();
    for (int i = 0; i < posting.lines().size(); i++) {
      EntryLine line = posting.lines().get(i);
      entries.add(new PostedEntry(entryIds.get(i), line.accountId(), line.side(), line.amount()));
    }
    entries = List.copyOf(entries);
    PostedTransaction transaction =
        new PostedTransaction(
            id,
            null,
            cmd.kind(),
            posting.currency(),
            posting.amount(),
            postedAt,
            cmd.description(),
            cmd.reversesId(),
            entries);
    UUID eventId = UuidV7.next(clock);
    TransactionEvent event = TransactionEvent.of(transaction, eventId, cmd.requestId(), accounts);
    outbox.insert(
        connection,
        eventId,
        id,
        entries.getFirst().accountId(),
        cmd.requestId(),
        event.toJson(),
        postedAt);
    UUID auditId = audit.record(connection, cmd.audit().withTransaction(id));
    return new PostedTransaction(
        id,
        auditId,
        cmd.kind(),
        posting.currency(),
        posting.amount(),
        postedAt,
        cmd.description(),
        cmd.reversesId(),
        entries);
  }
}
