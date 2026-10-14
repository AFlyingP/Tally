package tally.ledger.schedule;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import tally.ledger.api.CanonicalJson;
import tally.ledger.api.ScheduleDto;
import tally.ledger.audit.AuditEntry;
import tally.ledger.audit.AuditWriter;
import tally.ledger.core.AccountKind;
import tally.ledger.core.AccountState;
import tally.ledger.core.AccountStatus;
import tally.ledger.core.Frequency;
import tally.ledger.core.LedgerRuleException;
import tally.ledger.core.Recurrence;
import tally.ledger.core.RequestHash;
import tally.ledger.core.RuleCode;
import tally.ledger.idempotency.IdempotentExecutor;
import tally.ledger.idempotency.IdempotentExecutor.Result;
import tally.ledger.idempotency.Outcome;
import tally.platform.error.ApiError;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.id.UuidV7;
import tally.platform.security.Caller;
import tally.platform.web.RequestIdFilter;

@Service
public class ScheduleService {

  private static final Set<String> CURRENCIES =
      Currency.getAvailableCurrencies().stream()
          .map(Currency::getCurrencyCode)
          .collect(Collectors.toUnmodifiableSet());

  private final DataSource dataSource;
  private final ScheduleStore schedules;
  private final AuditWriter audit;
  private final Clock clock;
  private final IdempotentExecutor executor;

  public ScheduleService(
      DataSource dataSource,
      ScheduleStore schedules,
      AuditWriter audit,
      Clock clock,
      IdempotentExecutor executor) {
    this.dataSource = dataSource;
    this.schedules = schedules;
    this.audit = audit;
    this.clock = clock;
    this.executor = executor;
  }

  public Result create(Caller caller, String key, ScheduleDto.Create request) {
    validate(request);
    LocalDate firstDate =
        Recurrence.first(request.frequency(), request.startAt(), request.dayOfMonth());
    Instant now = now();
    UUID id = UuidV7.next(clock);
    String requestId = RequestIdFilter.current();
    byte[] hash = RequestHash.of("POST", "/v1/schedules", CanonicalJson.of(request));
    return executor.execute(
        caller,
        key,
        hash,
        (tx, keyId) -> {
          Map<UUID, AccountState> accounts =
              tx.accounts(
                  new TreeSet<>(
                      List.of(request.sourceAccountId(), request.destinationAccountId())));
          if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new LedgerRuleException(RuleCode.SAME_ACCOUNT, "accounts must be different");
          }
          requireAccount(accounts, request.sourceAccountId(), request.currency());
          requireAccount(accounts, request.destinationAccountId(), request.currency());
          ScheduleStore.ScheduleRow schedule =
              new ScheduleStore.ScheduleRow(
                  id,
                  request.sourceAccountId(),
                  request.destinationAccountId(),
                  request.currency(),
                  request.amount(),
                  request.frequency(),
                  request.startAt(),
                  request.dayOfMonth(),
                  request.endDate(),
                  "ACTIVE",
                  request.description(),
                  keyId,
                  caller.clientId(),
                  now,
                  now);
          schedules.insert(tx.connection(), schedule);
          schedules.insertOccurrence(
              tx.connection(),
              UuidV7.next(clock),
              id,
              firstDate,
              Recurrence.dueAt(firstDate, request.startAt()),
              now);
          audit.record(
              tx.connection(),
              AuditEntry.success(caller, "schedule.create", "schedule", id.toString(), requestId));
          return new Outcome(201, toDto(schedule));
        });
  }

  public ScheduleDto.Schedule get(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      return toDto(requireSchedule(connection, id));
    }
  }

  public ScheduleDto.OccurrenceList occurrences(UUID id, int limit) throws SQLException {
    if (limit < 1 || limit > 500) {
      throw invalid("limit", "must be between 1 and 500");
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      requireSchedule(connection, id);
      List<ScheduleDto.Occurrence> items =
          schedules.occurrences(connection, id, limit).stream()
              .map(ScheduleService::toDto)
              .toList();
      return new ScheduleDto.OccurrenceList(items);
    }
  }

  public ScheduleDto.Schedule cancel(Caller caller, UUID id, String requestId) throws SQLException {
    Instant now = now();
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      if (schedules.cancel(connection, id, now) == 0) {
        if (schedules.find(connection, id).isEmpty()) {
          throw new ApiException(ErrorCode.NOT_FOUND, "schedule not found");
        }
        throw new ApiException(ErrorCode.SCHEDULE_NOT_ACTIVE, "schedule is not active");
      }
      schedules.skipPending(connection, id, "CANCELLED", now);
      audit.record(
          connection,
          AuditEntry.success(caller, "schedule.cancel", "schedule", id.toString(), requestId));
      ScheduleDto.Schedule result = toDto(schedules.find(connection, id).orElseThrow());
      connection.commit();
      return result;
    }
  }

  private void validate(ScheduleDto.Create request) {
    if (request.amount() == null || request.amount() < 1) {
      throw invalid("amount", "must be at least 1");
    }
    if (request.frequency() == null) {
      throw invalid("frequency", "must be a supported frequency");
    }
    if ((request.frequency() == Frequency.MONTHLY) != (request.dayOfMonth() != null)
        || (request.dayOfMonth() != null
            && (request.dayOfMonth() < 1 || request.dayOfMonth() > 31))) {
      throw invalid("day_of_month", "must be between 1 and 31 for monthly schedules only");
    }
    if (request.frequency() == Frequency.ONCE && request.endDate() != null) {
      throw invalid("end_date", "must be absent for once schedules");
    }
    if (request.startAt() == null) {
      throw invalid("start_at", "is required");
    }
    Instant now = now();
    if (!request.startAt().isAfter(now)
        || request.startAt().isAfter(now.plus(366, ChronoUnit.DAYS))) {
      throw invalid("start_at", "must be after now and within 366 days");
    }
    if (request.endDate() != null
        && request
            .endDate()
            .isBefore(
                Recurrence.first(request.frequency(), request.startAt(), request.dayOfMonth()))) {
      throw invalid("end_date", "must not be before the first occurrence");
    }
    if (request.sourceAccountId() == null || request.destinationAccountId() == null) {
      throw invalid("source_account_id", "both account IDs are required");
    }
    if (request.currency() == null || !CURRENCIES.contains(request.currency())) {
      throw invalid("currency", "must be a known 3-letter currency code");
    }
  }

  private static void requireAccount(Map<UUID, AccountState> accounts, UUID id, String currency) {
    AccountState account = accounts.get(id);
    if (account == null) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_NOT_FOUND, "account not found");
    }
    if (account.kind() != AccountKind.CUSTOMER) {
      throw new LedgerRuleException(
          RuleCode.INTERNAL_ACCOUNT, "account must be a customer account");
    }
    if (account.status() == AccountStatus.FROZEN) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_FROZEN, "account is frozen");
    }
    if (account.status() == AccountStatus.CLOSED) {
      throw new LedgerRuleException(RuleCode.ACCOUNT_CLOSED, "account is closed");
    }
    if (!currency.equals(account.currency())) {
      throw new LedgerRuleException(RuleCode.CURRENCY_MISMATCH, "account currency does not match");
    }
  }

  private ScheduleStore.ScheduleRow requireSchedule(Connection connection, UUID id)
      throws SQLException {
    return schedules
        .find(connection, id)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "schedule not found"));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static ScheduleDto.Schedule toDto(ScheduleStore.ScheduleRow row) {
    return new ScheduleDto.Schedule(
        row.id(),
        row.sourceAccountId(),
        row.destinationAccountId(),
        row.currency(),
        row.amount(),
        row.frequency(),
        row.startAt(),
        row.dayOfMonth(),
        row.endDate(),
        row.status(),
        row.description(),
        row.createdAt());
  }

  private static ScheduleDto.Occurrence toDto(ScheduleStore.OccurrenceRow row) {
    return new ScheduleDto.Occurrence(
        row.id(),
        row.occurrenceDate(),
        row.dueAt(),
        row.state(),
        row.attempts(),
        row.nextAttemptAt(),
        row.transactionId(),
        row.reason());
  }

  private static ApiException invalid(String field, String issue) {
    return new ApiException(
        ErrorCode.VALIDATION_FAILED,
        "request is not valid",
        List.of(new ApiError.Detail(field, issue)));
  }
}
