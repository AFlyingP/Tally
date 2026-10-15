package tally.extbank;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;

@Service
public class PaymentService {

  public record SubmitResult(int status, ExtbankDto.BankPayment body) {}

  private final DataSource dataSource;
  private final PaymentStore payments;
  private final RuleStore rules;
  private final Clock clock;

  public PaymentService(
      DataSource dataSource, PaymentStore payments, RuleStore rules, Clock clock) {
    this.dataSource = dataSource;
    this.payments = payments;
    this.rules = rules;
    this.clock = clock;
  }

  public SubmitResult submit(ExtbankDto.Submit request) {
    Submission submission;
    try {
      submission = inTransaction(connection -> submitIn(connection, request));
    } catch (SQLException firstFailure) {
      if (!"23505".equals(firstFailure.getSQLState())) {
        throw new ApiException(ErrorCode.INTERNAL_ERROR, "payment database operation failed");
      }
      try {
        submission = inTransaction(connection -> submitIn(connection, request));
      } catch (SQLException retryFailure) {
        throw new ApiException(ErrorCode.INTERNAL_ERROR, "payment database operation failed");
      }
    }
    SubmitResult result = submission.result();
    Behaviour behaviour = submission.behaviour();
    if (behaviour == Behaviour.SERVER_ERROR) {
      throw new ApiException(ErrorCode.INTERNAL_ERROR, "simulated server error");
    }
    if (behaviour == Behaviour.DROP_RESPONSE) {
      waitFor(submission.delayMs());
      return new SubmitResult(504, null);
    }
    if (behaviour == Behaviour.DELAY) {
      waitFor(submission.delayMs());
      LocalDate today = LocalDate.now(clock);
      try {
        inTransaction(
            connection -> {
              payments.confirm(connection, request.reference(), today, 1);
              return null;
            });
        return new SubmitResult(200, get(request.reference()).orElseThrow());
      } catch (SQLException e) {
        throw new ApiException(ErrorCode.INTERNAL_ERROR, "payment confirmation failed");
      }
    }
    return result;
  }

  public Optional<ExtbankDto.BankPayment> get(String reference) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      return payments.find(connection, reference);
    }
  }

  private Submission submitIn(Connection connection, ExtbankDto.Submit request)
      throws SQLException {
    Optional<ExtbankDto.BankPayment> existing = payments.find(connection, request.reference());
    if (existing.isPresent()) {
      ExtbankDto.BankPayment stored = existing.orElseThrow();
      if (!stored.creditorAccount().equals(request.creditorAccount())
          || stored.amount() != request.amount()
          || !stored.currency().equals(request.currency())) {
        throw new ApiException(
            ErrorCode.REFERENCE_CONFLICT, "reference has different payment data");
      }
      return new Submission(new SubmitResult(200, stored), null);
    }

    ExtbankDto.Rule rule = rules.select(connection, request.creditorAccount()).orElse(null);
    Behaviour selected = rule == null ? Behaviour.ACCEPT : rule.behaviour();
    int delay = rule == null ? 0 : rule.delayMs();
    LocalDate today = LocalDate.now(clock);
    ExtbankDto.BankPayment payment = payment(request, selected, today);
    if (selected != Behaviour.SERVER_ERROR) {
      payments.insert(connection, payment, Instant.now(clock));
    }
    if (selected == Behaviour.DROP_RESPONSE) {
      delay = delay > 0 ? delay : 10000;
    }
    return new Submission(
        new SubmitResult(selected == Behaviour.DROP_RESPONSE ? 504 : 200, payment),
        selected,
        delay);
  }

  private ExtbankDto.BankPayment payment(
      ExtbankDto.Submit request, Behaviour behaviour, LocalDate today) {
    return switch (behaviour) {
      case ACCEPT ->
          new ExtbankDto.BankPayment(
              request.reference(),
              request.creditorAccount(),
              request.amount(),
              request.currency(),
              "CONFIRMED",
              null,
              today,
              1);
      case REJECT ->
          new ExtbankDto.BankPayment(
              request.reference(),
              request.creditorAccount(),
              request.amount(),
              request.currency(),
              "REJECTED",
              "rejected by rule",
              null,
              0);
      case DELAY ->
          new ExtbankDto.BankPayment(
              request.reference(),
              request.creditorAccount(),
              request.amount(),
              request.currency(),
              "PENDING",
              null,
              null,
              0);
      case DROP_RESPONSE ->
          new ExtbankDto.BankPayment(
              request.reference(),
              request.creditorAccount(),
              request.amount(),
              request.currency(),
              "CONFIRMED",
              null,
              today,
              1);
      case CONFIRM_TWICE ->
          new ExtbankDto.BankPayment(
              request.reference(),
              request.creditorAccount(),
              request.amount(),
              request.currency(),
              "CONFIRMED",
              null,
              today,
              2);
      case SERVER_ERROR -> null;
    };
  }

  private <T> T inTransaction(SqlWork<T> work) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      }
    }
  }

  private static void waitFor(int delayMs) {
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(ErrorCode.INTERNAL_ERROR, "payment wait was interrupted");
    }
  }

  private record Submission(SubmitResult result, Behaviour behaviour, int delayMs) {
    Submission(SubmitResult result, Behaviour behaviour) {
      this(result, behaviour, 0);
    }
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }
}
