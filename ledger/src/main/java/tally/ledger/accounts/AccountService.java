package tally.ledger.accounts;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import tally.ledger.api.AccountDto;
import tally.ledger.api.Cursor;
import tally.ledger.audit.AuditEntry;
import tally.ledger.audit.AuditWriter;
import tally.ledger.core.AccountKind;
import tally.ledger.core.AccountState;
import tally.ledger.core.AccountStatus;
import tally.ledger.core.Side;
import tally.ledger.posting.RetryableConflict;
import tally.ledger.posting.StrategyRunner;
import tally.ledger.store.AccountStore;
import tally.ledger.store.EntryStore;
import tally.platform.error.ApiError;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.id.UuidV7;
import tally.platform.security.Caller;

@Service
public class AccountService {

  private static final long LOWEST_MIN_BALANCE = -4_000_000_000_000_000_000L;
  private static final Set<String> CURRENCIES =
      Currency.getAvailableCurrencies().stream()
          .map(Currency::getCurrencyCode)
          .collect(Collectors.toUnmodifiableSet());

  private final DataSource dataSource;
  private final AccountStore accounts;
  private final EntryStore entries;
  private final AuditWriter audit;
  private final Clock clock;
  private final StrategyRunner runner;

  public AccountService(
      DataSource dataSource,
      AccountStore accounts,
      EntryStore entries,
      AuditWriter audit,
      Clock clock,
      StrategyRunner runner) {
    this.dataSource = dataSource;
    this.accounts = accounts;
    this.entries = entries;
    this.audit = audit;
    this.clock = clock;
    this.runner = runner;
  }

  /** Creates a customer account, the internal accounts of its currency, and the audit row. */
  public AccountDto.Account create(
      Caller caller, AccountDto.CreateAccount request, String requestId) throws SQLException {
    if (request.currency() == null || !CURRENCIES.contains(request.currency())) {
      throw invalid("currency", "must be a known 3-letter currency code");
    }
    long minBalance = request.minBalance() == null ? 0 : request.minBalance();
    if (minBalance < LOWEST_MIN_BALANCE || minBalance > 0) {
      throw invalid("min_balance", "must be between " + LOWEST_MIN_BALANCE + " and 0");
    }
    Side normalSide = request.normalSide() == null ? Side.CREDIT : request.normalSide();
    UUID id = UuidV7.next(clock);
    Instant now = clock.instant();

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        accounts.ensureInternal(connection, request.currency(), now);
        accounts.insertCustomer(connection, id, request.currency(), normalSide, minBalance, now);
        audit.record(
            connection,
            AuditEntry.success(caller, "account.create", "account", id.toString(), requestId));
        AccountStore.AccountRow row = accounts.find(connection, id).orElseThrow();
        connection.commit();
        return new AccountDto.Account(
            row.id(),
            row.kind(),
            row.currency(),
            row.normalSide(),
            row.minBalance(),
            row.status(),
            row.createdAt());
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      }
    }
  }

  public AccountDto.Account get(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      AccountStore.AccountRow row = findAccount(connection, id);
      return new AccountDto.Account(
          row.id(),
          row.kind(),
          row.currency(),
          row.normalSide(),
          row.minBalance(),
          row.status(),
          row.createdAt());
    }
  }

  public AccountDto.Account freeze(Caller caller, UUID id, String requestId) {
    return changeStatus(caller, id, requestId, AccountStatus.FROZEN);
  }

  public AccountDto.Account close(Caller caller, UUID id, String requestId) {
    return changeStatus(caller, id, requestId, AccountStatus.CLOSED);
  }

  private AccountDto.Account changeStatus(
      Caller caller, UUID id, String requestId, AccountStatus newStatus) {
    String action = newStatus == AccountStatus.FROZEN ? "account.freeze" : "account.close";
    return runner
        .execute(
            tx -> {
              Map<UUID, AccountState> loaded = tx.accounts(new TreeSet<>(Set.of(id)));
              AccountState account = loaded.get(id);
              if (account == null) {
                throw new ApiException(ErrorCode.NOT_FOUND, "account not found");
              }
              if (account.kind() != AccountKind.CUSTOMER) {
                throw new ApiException(ErrorCode.INTERNAL_ACCOUNT, "account is not a customer");
              }
              if (newStatus == AccountStatus.FROZEN) {
                if (account.status() == AccountStatus.FROZEN) {
                  throw new ApiException(ErrorCode.ACCOUNT_FROZEN, "account is frozen");
                }
                if (account.status() == AccountStatus.CLOSED) {
                  throw new ApiException(ErrorCode.ACCOUNT_CLOSED, "account is closed");
                }
              } else {
                if (account.status() == AccountStatus.CLOSED) {
                  throw new ApiException(ErrorCode.ACCOUNT_CLOSED, "account is closed");
                }
                if (account.balance() != 0 || account.held() != 0) {
                  throw new ApiException(ErrorCode.ACCOUNT_NOT_EMPTY, "account is not empty");
                }
              }
              Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
              if (accounts.updateStatus(tx.connection(), id, newStatus, account.version(), now)
                  == 0) {
                throw new RetryableConflict("version");
              }
              audit.record(
                  tx.connection(),
                  AuditEntry.success(caller, action, "account", id.toString(), requestId));
              AccountStore.AccountRow row = accounts.find(tx.connection(), id).orElseThrow();
              return new AccountDto.Account(
                  row.id(),
                  row.kind(),
                  row.currency(),
                  row.normalSide(),
                  row.minBalance(),
                  row.status(),
                  row.createdAt());
            })
        .value();
  }

  public AccountDto.Balance balance(UUID id, Instant asOfOrNull) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      AccountStore.AccountRow row = findAccount(connection, id);
      if (asOfOrNull == null) {
        return new AccountDto.Balance(
            row.id(),
            row.currency(),
            row.balance(),
            row.held(),
            row.balance() - row.held(),
            clock.instant());
      }
      if (asOfOrNull.isAfter(clock.instant())) {
        throw invalid("as_of", "must not be in the future");
      }
      return new AccountDto.Balance(
          row.id(),
          row.currency(),
          entries.balanceAsOf(connection, id, asOfOrNull),
          null,
          null,
          asOfOrNull);
    }
  }

  public AccountDto.EntryPage entries(UUID id, int limit, String cursorOrNull) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      findAccount(connection, id);
      if (limit < 1 || limit > 200) {
        throw invalid("limit", "must be between 1 and 200");
      }
      long afterId = cursorOrNull == null ? 0 : Cursor.decodeLong(cursorOrNull);
      List<AccountDto.Entry> page = entries.page(connection, id, afterId, limit + 1);
      String nextCursor = null;
      if (page.size() > limit) {
        page = page.subList(0, limit);
        nextCursor = Cursor.encode(String.valueOf(page.get(page.size() - 1).id()));
      }
      return new AccountDto.EntryPage(page, nextCursor);
    }
  }

  private AccountStore.AccountRow findAccount(Connection connection, UUID id) throws SQLException {
    Optional<AccountStore.AccountRow> row = accounts.find(connection, id);
    return row.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "account not found"));
  }

  private static ApiException invalid(String field, String issue) {
    return new ApiException(
        ErrorCode.VALIDATION_FAILED,
        "request is not valid",
        List.of(new ApiError.Detail(field, issue)));
  }
}
