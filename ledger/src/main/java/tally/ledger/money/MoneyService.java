package tally.ledger.money;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tally.ledger.api.CanonicalJson;
import tally.ledger.api.MoneyDto;
import tally.ledger.api.TransactionDto;
import tally.ledger.audit.AuditEntry;
import tally.ledger.core.AccountKind;
import tally.ledger.core.AccountState;
import tally.ledger.core.EntryLine;
import tally.ledger.core.LedgerRuleException;
import tally.ledger.core.Posting;
import tally.ledger.core.RequestHash;
import tally.ledger.core.RuleCode;
import tally.ledger.core.Side;
import tally.ledger.core.TransactionKind;
import tally.ledger.idempotency.IdempotentExecutor;
import tally.ledger.idempotency.IdempotentExecutor.Result;
import tally.ledger.idempotency.Outcome;
import tally.ledger.posting.LedgerTx;
import tally.ledger.posting.LedgerWriter;
import tally.ledger.posting.PostedTransaction;
import tally.ledger.posting.PostingCommand;
import tally.platform.error.ApiError;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.security.Caller;
import tally.platform.web.RequestIdFilter;

@Service
public class MoneyService {

  private static final Set<String> CURRENCIES =
      Currency.getAvailableCurrencies().stream()
          .map(Currency::getCurrencyCode)
          .collect(Collectors.toUnmodifiableSet());

  private final IdempotentExecutor executor;
  private final LedgerWriter writer;

  public MoneyService(IdempotentExecutor executor, LedgerWriter writer) {
    this.executor = executor;
    this.writer = writer;
  }

  public Result deposit(Caller caller, String key, MoneyDto.Deposit request) {
    requireCurrency(request.currency());
    byte[] hash = RequestHash.of("POST", "/v1/deposits", CanonicalJson.of(request));
    return executor.execute(
        caller,
        key,
        hash,
        (tx, keyId) -> {
          UUID settlement = settlement(tx, request.currency());
          Map<UUID, AccountState> accounts =
              tx.accounts(new TreeSet<>(List.of(request.accountId(), settlement)));
          requireCustomer(accounts, request.accountId());
          Posting posting =
              Posting.of(
                  request.currency(),
                  List.of(
                      new EntryLine(request.accountId(), Side.CREDIT, request.amount()),
                      new EntryLine(settlement, Side.DEBIT, request.amount())));
          return post(
              tx,
              keyId,
              caller,
              TransactionKind.DEPOSIT,
              posting,
              request.description(),
              "deposit.post");
        });
  }

  public Result withdraw(Caller caller, String key, MoneyDto.Withdrawal request) {
    requireCurrency(request.currency());
    byte[] hash = RequestHash.of("POST", "/v1/withdrawals", CanonicalJson.of(request));
    return executor.execute(
        caller,
        key,
        hash,
        (tx, keyId) -> {
          UUID settlement = settlement(tx, request.currency());
          Map<UUID, AccountState> accounts =
              tx.accounts(new TreeSet<>(List.of(request.accountId(), settlement)));
          requireCustomer(accounts, request.accountId());
          Posting posting =
              Posting.move(request.currency(), request.accountId(), settlement, request.amount());
          return post(
              tx,
              keyId,
              caller,
              TransactionKind.WITHDRAWAL,
              posting,
              request.description(),
              "withdrawal.post");
        });
  }

  public Result transfer(Caller caller, String key, MoneyDto.Transfer request) {
    requireCurrency(request.currency());
    byte[] hash = RequestHash.of("POST", "/v1/transfers", CanonicalJson.of(request));
    return executor.execute(
        caller,
        key,
        hash,
        (tx, keyId) -> {
          Map<UUID, AccountState> accounts =
              tx.accounts(
                  new TreeSet<>(
                      List.of(request.sourceAccountId(), request.destinationAccountId())));
          requireCustomer(accounts, request.sourceAccountId());
          requireCustomer(accounts, request.destinationAccountId());
          Posting posting =
              Posting.move(
                  request.currency(),
                  request.sourceAccountId(),
                  request.destinationAccountId(),
                  request.amount());
          return post(
              tx,
              keyId,
              caller,
              TransactionKind.TRANSFER,
              posting,
              request.description(),
              "transfer.post");
        });
  }

  private Outcome post(
      LedgerTx tx,
      long keyId,
      Caller caller,
      TransactionKind kind,
      Posting posting,
      String description,
      String action)
      throws SQLException {
    String requestId = RequestIdFilter.current();
    AuditEntry audit = AuditEntry.success(caller, action, "transaction", "-", requestId);
    PostedTransaction posted =
        writer.post(
            tx,
            new PostingCommand(
                kind, posting, keyId, null, description, Map.of(), audit, caller, requestId));
    return new Outcome(201, TransactionDto.Transaction.from(posted));
  }

  private static UUID settlement(LedgerTx tx, String currency) throws SQLException {
    try (PreparedStatement select =
        tx.connection()
            .prepareStatement(
                "SELECT id FROM ledger.account WHERE kind = 'SETTLEMENT' AND currency = ?")) {
      select.setString(1, currency);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          throw new LedgerRuleException(RuleCode.CURRENCY_MISMATCH, "settlement account not found");
        }
        return row.getObject("id", UUID.class);
      }
    }
  }

  private static void requireCustomer(Map<UUID, AccountState> accounts, UUID id) {
    AccountState account = accounts.get(id);
    if (account == null) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_NOT_FOUND, "account not found");
    }
    if (account.kind() != AccountKind.CUSTOMER) {
      throw new LedgerRuleException(
          RuleCode.INTERNAL_ACCOUNT, "account must be a customer account");
    }
  }

  private static void requireCurrency(String currency) {
    if (currency == null || !CURRENCIES.contains(currency)) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED,
          "request is not valid",
          List.of(new ApiError.Detail("currency", "must be a known 3-letter currency code")));
    }
  }
}
