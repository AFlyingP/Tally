package tally.ledger.money;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import tally.ledger.api.CanonicalJson;
import tally.ledger.api.MoneyDto;
import tally.ledger.api.TransactionDto;
import tally.ledger.audit.AuditEntry;
import tally.ledger.core.EntryLine;
import tally.ledger.core.LedgerRuleException;
import tally.ledger.core.Posting;
import tally.ledger.core.RequestHash;
import tally.ledger.core.RuleCode;
import tally.ledger.core.TransactionKind;
import tally.ledger.idempotency.IdempotentExecutor;
import tally.ledger.idempotency.IdempotentExecutor.Result;
import tally.ledger.idempotency.Outcome;
import tally.ledger.posting.LedgerWriter;
import tally.ledger.posting.PostedTransaction;
import tally.ledger.posting.PostingCommand;
import tally.ledger.posting.RetryableConflict;
import tally.ledger.store.TransactionStore;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.security.Caller;
import tally.platform.web.RequestIdFilter;

@Service
public class ReversalService {

  private static final Set<TransactionKind> REVERSIBLE_KINDS =
      Set.of(TransactionKind.DEPOSIT, TransactionKind.WITHDRAWAL, TransactionKind.TRANSFER);

  private final DataSource dataSource;
  private final TransactionStore transactions;
  private final IdempotentExecutor executor;
  private final LedgerWriter writer;

  public ReversalService(
      DataSource dataSource,
      TransactionStore transactions,
      IdempotentExecutor executor,
      LedgerWriter writer) {
    this.dataSource = dataSource;
    this.transactions = transactions;
    this.executor = executor;
    this.writer = writer;
  }

  public Result reverse(Caller caller, String key, UUID transactionId, MoneyDto.Reversal request)
      throws SQLException {
    TransactionDto.Transaction original = requireOriginal(transactionId);
    MoneyDto.Reversal normalized = request == null ? new MoneyDto.Reversal(null) : request;
    String path = "/v1/transactions/" + transactionId + "/reversal";
    byte[] hash = RequestHash.of("POST", path, CanonicalJson.of(normalized));
    return executor.execute(
        caller,
        key,
        hash,
        (tx, keyId) -> {
          tx.accounts(
              new TreeSet<>(
                  original.entries().stream().map(TransactionDto.Entry::accountId).toList()));
          TransactionDto.Transaction current =
              transactions.find(tx.connection(), transactionId).orElseThrow();
          if (!REVERSIBLE_KINDS.contains(current.kind())) {
            throw new LedgerRuleException(
                RuleCode.NOT_REVERSIBLE, "transaction cannot be reversed");
          }
          if (current.reversedByTransactionId() != null) {
            throw new LedgerRuleException(
                RuleCode.ALREADY_REVERSED, "transaction is already reversed");
          }
          Posting posting =
              Posting.of(
                      current.currency(),
                      current.entries().stream()
                          .map(
                              entry ->
                                  new EntryLine(entry.accountId(), entry.side(), entry.amount()))
                          .toList())
                  .reversed();
          String description =
              normalized.reason() == null ? "reversal of " + current.id() : normalized.reason();
          String requestId = RequestIdFilter.current();
          AuditEntry audit =
              AuditEntry.success(caller, "transaction.reverse", "transaction", "-", requestId);
          PostedTransaction posted;
          try {
            posted =
                writer.post(
                    tx,
                    new PostingCommand(
                        TransactionKind.REVERSAL,
                        posting,
                        keyId,
                        current.id(),
                        description,
                        Map.of(),
                        audit,
                        caller,
                        requestId));
          } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())
                && e.getMessage().contains("ledger_transaction_reverses_uq")) {
              throw new RetryableConflict("23505");
            }
            throw e;
          }
          return new Outcome(201, TransactionDto.Transaction.from(posted));
        });
  }

  private TransactionDto.Transaction requireOriginal(UUID transactionId) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      return transactions
          .find(connection, transactionId)
          .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "transaction not found"));
    }
  }
}
