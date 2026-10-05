package tally.ledger.accounts;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import tally.ledger.api.AccountDto;
import tally.ledger.audit.AuditEntry;
import tally.ledger.audit.AuditWriter;
import tally.ledger.core.Side;
import tally.ledger.store.AccountStore;
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
  private final AuditWriter audit;
  private final Clock clock;

  public AccountService(
      DataSource dataSource, AccountStore accounts, AuditWriter audit, Clock clock) {
    this.dataSource = dataSource;
    this.accounts = accounts;
    this.audit = audit;
    this.clock = clock;
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

  private static ApiException invalid(String field, String issue) {
    return new ApiException(
        ErrorCode.VALIDATION_FAILED,
        "request is not valid",
        List.of(new ApiError.Detail(field, issue)));
  }
}
