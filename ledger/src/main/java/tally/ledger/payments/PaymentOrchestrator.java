package tally.ledger.payments;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tally.ledger.config.JobConfig;
import tally.ledger.config.LedgerProperties;
import tally.ledger.core.PaymentFlow;
import tally.ledger.core.PaymentState;
import tally.ledger.failpoint.Failpoints;
import tally.ledger.payments.PaymentStore.PaymentRow;
import tally.ledger.posting.StrategyRunner;

@Component
public class PaymentOrchestrator implements JobConfig.Job {

  private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrator.class);
  private final String owner;
  private final PaymentStore payments;
  private final PaymentSteps steps;
  private final ExtbankClient bank;
  private final StrategyRunner runner;
  private final LedgerProperties.Payments settings;
  private final Clock clock;

  public PaymentOrchestrator(
      PaymentStore payments,
      PaymentSteps steps,
      ExtbankClient bank,
      StrategyRunner runner,
      LedgerProperties properties,
      Clock clock) {
    this.owner = UUID.randomUUID().toString();
    this.payments = payments;
    this.steps = steps;
    this.bank = bank;
    this.runner = runner;
    this.settings = properties.payments();
    this.clock = clock;
  }

  @Override
  public String name() {
    return "orchestrator";
  }

  @Override
  public String role() {
    return "orchestrator";
  }

  @Override
  public long pollMs() {
    return settings.pollMs();
  }

  @Override
  public int runOnce() {
    List<PaymentRow> claimed;
    try {
      Instant now = now();
      claimed =
          runner
              .execute(
                  tx ->
                      payments.claim(
                          tx.connection(), owner, now, now.plusMillis(settings.leaseMs()), 10))
              .value();
    } catch (Exception exception) {
      log.error("payment claim failed", exception);
      return 0;
    }
    for (PaymentRow payment : claimed) {
      try {
        Optional<PaymentRow> current = Optional.of(payment);
        while (current.isPresent() && !current.get().state().terminal()) {
          current = advance(current.get());
        }
      } catch (Exception exception) {
        log.error("payment processing failed: payment={}", payment.id(), exception);
      } finally {
        try {
          runner.execute(
              tx -> {
                payments.releaseLease(tx.connection(), payment.id(), owner);
                return null;
              });
        } catch (Exception exception) {
          log.error("payment lease release failed: payment={}", payment.id(), exception);
        }
      }
    }
    return claimed.size();
  }

  private Optional<PaymentRow> advance(PaymentRow payment) {
    return switch (payment.state()) {
      case INITIATED -> steps.hold(payment);
      case HELD -> steps.capture(payment);
      case CAPTURED -> {
        Optional<PaymentRow> submitting = steps.markSubmitting(payment);
        if (submitting.isEmpty()) {
          yield Optional.empty();
        }
        ExtbankClient.SubmitResult result = submit(submitting.get());
        Failpoints.hit("payment.after_submit");
        yield resolve(submitting.get(), result);
      }
      case SUBMITTING -> steps.markUnknown(payment, 0, now(), "resumed");
      case UNKNOWN -> {
        ExtbankClient.StatusResult result =
            bank.status(payment.externalReference(), payment.requestId());
        yield resolve(payment, result instanceof ExtbankClient.NotFound ? submit(payment) : result);
      }
      case SETTLED, REJECTED_COMPENSATED, MANUAL_REVIEW, FAILED -> Optional.empty();
    };
  }

  private ExtbankClient.SubmitResult submit(PaymentRow payment) {
    return bank.submit(
        payment.externalReference(),
        payment.creditorAccount(),
        payment.amount(),
        payment.currency(),
        payment.requestId());
  }

  private Optional<PaymentRow> resolve(PaymentRow payment, Object result) {
    if (result instanceof ExtbankClient.Confirmed confirmed) {
      return steps.settle(payment, confirmed.valueDate());
    }
    if (result instanceof ExtbankClient.Rejected rejected) {
      return steps.compensate(payment, rejected.reason());
    }
    if (result instanceof ExtbankClient.Conflict) {
      return steps.manualReview(payment, "reference conflict");
    }
    int attempts = payment.state() == PaymentState.SUBMITTING ? 0 : payment.statusAttempts() + 1;
    if (attempts == 8) {
      return steps.manualReview(payment, "unresolved after 8 status queries");
    }
    long delay = PaymentFlow.statusDelayMs(attempts, settings.statusBackoffMs()).orElseThrow();
    String detail = result instanceof ExtbankClient.Unknown unknown ? unknown.cause() : null;
    steps.markUnknown(payment, attempts, now().plusMillis(delay), detail);
    return Optional.empty();
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
