package tally.ledger.payments;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
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
import tally.ledger.api.PaymentDto;
import tally.ledger.audit.AuditEntry;
import tally.ledger.audit.AuditWriter;
import tally.ledger.core.AccountKind;
import tally.ledger.core.AccountState;
import tally.ledger.core.AccountStatus;
import tally.ledger.core.LedgerRuleException;
import tally.ledger.core.PaymentState;
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
public class PaymentService {

  private static final Set<String> CURRENCIES =
      Currency.getAvailableCurrencies().stream()
          .map(Currency::getCurrencyCode)
          .collect(Collectors.toUnmodifiableSet());

  private final DataSource dataSource;
  private final PaymentStore payments;
  private final AuditWriter audit;
  private final Clock clock;
  private final IdempotentExecutor executor;

  public PaymentService(
      DataSource dataSource,
      PaymentStore payments,
      AuditWriter audit,
      Clock clock,
      IdempotentExecutor executor) {
    this.dataSource = dataSource;
    this.payments = payments;
    this.audit = audit;
    this.clock = clock;
    this.executor = executor;
  }

  public Result create(Caller caller, String key, PaymentDto.Create request) {
    requireCurrency(request.currency());
    byte[] hash = RequestHash.of("POST", "/v1/external-payments", CanonicalJson.of(request));
    String requestId = RequestIdFilter.current();
    return executor.execute(
        caller,
        key,
        hash,
        (tx, keyId) -> {
          Map<UUID, AccountState> accounts =
              tx.accounts(new TreeSet<>(Set.of(request.sourceAccountId())));
          AccountState account = accounts.get(request.sourceAccountId());
          if (account == null) {
            throw new LedgerRuleException(RuleCode.ACCOUNT_NOT_FOUND, "account not found");
          }
          if (account.kind() != AccountKind.CUSTOMER) {
            throw new LedgerRuleException(
                RuleCode.INTERNAL_ACCOUNT, "account must be a customer account");
          }
          if (!request.currency().equals(account.currency())) {
            throw new LedgerRuleException(
                RuleCode.CURRENCY_MISMATCH, "account currency does not match");
          }
          if (account.status() == AccountStatus.FROZEN) {
            throw new LedgerRuleException(RuleCode.ACCOUNT_FROZEN, "account is frozen");
          }
          if (account.status() == AccountStatus.CLOSED) {
            throw new LedgerRuleException(RuleCode.ACCOUNT_CLOSED, "account is closed");
          }
          Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          UUID id = UuidV7.next(clock);
          String externalReference = "TLY-" + id.toString().replace("-", "").toUpperCase();
          PaymentStore.PaymentRow row =
              new PaymentStore.PaymentRow(
                  id,
                  account.id(),
                  request.currency(),
                  request.amount(),
                  request.creditorAccount(),
                  externalReference,
                  PaymentState.INITIATED,
                  null,
                  null,
                  null,
                  null,
                  0,
                  now,
                  null,
                  null,
                  null,
                  keyId,
                  caller.clientId(),
                  requestId,
                  now,
                  now);
          payments.insert(tx.connection(), row);
          UUID auditId =
              audit.record(
                  tx.connection(),
                  AuditEntry.success(
                      caller, "payment.create", "external_payment", id.toString(), requestId));
          payments.insertEvent(
              tx.connection(), id, null, PaymentState.INITIATED, null, now, auditId);
          PaymentDto.Event createdEvent =
              new PaymentDto.Event(null, PaymentState.INITIATED.name(), null, now);
          return new Outcome(202, toDto(row, List.of(createdEvent)));
        });
  }

  public PaymentDto.Payment get(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      PaymentStore.PaymentRow row =
          payments
              .find(connection, id)
              .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "payment not found"));
      List<PaymentDto.Event> events =
          payments.events(connection, id).stream()
              .map(
                  event ->
                      new PaymentDto.Event(
                          event.fromState(), event.toState(), event.detail(), event.occurredAt()))
              .toList();
      return toDto(row, events);
    }
  }

  private void requireCurrency(String currency) {
    if (currency == null || !CURRENCIES.contains(currency)) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED,
          "request is not valid",
          List.of(new ApiError.Detail("currency", "must be a known 3-letter currency code")));
    }
  }

  private static PaymentDto.Payment toDto(
      PaymentStore.PaymentRow row, List<PaymentDto.Event> events) {
    return new PaymentDto.Payment(
        row.id(),
        row.sourceAccountId(),
        row.currency(),
        row.amount(),
        row.creditorAccount(),
        row.externalReference(),
        row.state().name(),
        row.valueDate(),
        row.reason(),
        row.statusAttempts(),
        row.captureTransactionId(),
        row.resolutionTransactionId(),
        row.createdAt(),
        row.updatedAt(),
        events);
  }
}
