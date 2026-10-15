package tally.ledger.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tally.extbank.ExtbankApplication;
import tally.ledger.LedgerApplication;
import tally.ledger.check.InvariantChecker;
import tally.ledger.config.LedgerProperties;
import tally.ledger.core.PaymentState;
import tally.ledger.payments.PaymentStore.PaymentRow;
import tally.ledger.posting.StrategyRunner;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tally.platform.testing.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentOrchestratorIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String API_KEY = "k".repeat(48);
  private static final long AMOUNT = 120;
  private static final long[] BACKOFF = {5000, 10000, 20000, 40000, 75000, 120000, 150000, 180000};
  private final MutableClock clock = new MutableClock(Instant.parse("2035-01-01T10:00:00Z"));
  private ConfigurableApplicationContext bankContext;
  private ConfigurableApplicationContext context;
  private String bankUrl;
  private PaymentStore store;
  private PaymentSteps steps;
  private PaymentOrchestrator orchestrator;

  @BeforeAll
  void start() {
    bankContext =
        ExtbankApplication.builder()
            .initializers(
                initializer ->
                    ((GenericApplicationContext) initializer)
                        .registerBean(Clock.class, () -> clock))
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=extbank_app",
                "--spring.datasource.password=" + PASSWORD,
                "--spring.datasource.hikari.maximum-pool-size=4",
                "--tally.extbank.api-key=" + API_KEY);
    bankUrl = "http://localhost:" + bankContext.getEnvironment().getProperty("local.server.port");
    context = ledger("pessimistic");
    store = context.getBean(PaymentStore.class);
    steps = context.getBean(PaymentSteps.class);
    orchestrator = context.getBean(PaymentOrchestrator.class);
  }

  @BeforeEach
  void clearRules() {
    clock.advance(Duration.ofDays(1));
    assertThat(bank("DELETE", "/control/rules", null).statusCode()).isEqualTo(204);
    while (orchestrator.runOnce() > 0) {}
  }

  @AfterAll
  void stop() {
    if (context != null) {
      context.close();
    }
    if (bankContext != null) {
      bankContext.close();
    }
  }

  @Test
  void acceptedPaymentSettles() throws Exception {
    PaymentRow payment = payment(context, 1000);
    long clearing = internalBalance("CLEARING");
    long settlement = internalBalance("SETTLEMENT");

    orchestrator.runOnce();

    assertSettled(payment);
    assertThat(events(payment))
        .containsExactly("INITIATED", "HELD", "CAPTURED", "SUBMITTING", "SETTLED");
    assertThat(accountValue(payment.sourceAccountId(), "balance")).isEqualTo(1000 - AMOUNT);
    assertThat(accountValue(payment.sourceAccountId(), "held")).isZero();
    assertThat(internalBalance("CLEARING") - clearing).isZero();
    assertThat(internalBalance("SETTLEMENT") - settlement).isEqualTo(-AMOUNT);
    assertThat(row(payment).valueDate())
        .isEqualTo(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    assertThat(transactions(payment, "HOLD_CAPTURE")).isEqualTo(1);
  }

  @Test
  void rejectedPaymentIsCompensated() throws Exception {
    rule("REJECT", 0, null);
    PaymentRow payment = payment(context, 1000);
    long clearing = internalBalance("CLEARING");

    orchestrator.runOnce();

    assertCompensated(payment);
    assertThat(internalBalance("CLEARING") - clearing).isZero();
  }

  @Test
  void insufficientFundsFailsWithoutMovingMoney() throws Exception {
    PaymentRow payment = payment(context, 0);

    orchestrator.runOnce();

    assertThat(row(payment).state()).isEqualTo(PaymentState.FAILED);
    assertThat(row(payment).reason()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(row(payment).holdId()).isNull();
    assertThat(holdCount(payment)).isZero();
    assertThat(transactions(payment, null)).isZero();
    assertThat(accountValue(payment.sourceAccountId(), "balance")).isZero();
    assertThat(accountValue(payment.sourceAccountId(), "held")).isZero();
  }

  @Test
  void droppedResponseIsResolvedByStatusQuery() throws Exception {
    rule("DROP_RESPONSE", 600, null);
    PaymentRow payment = payment(context, 1000);

    orchestrator.runOnce();

    assertThat(row(payment).state()).isEqualTo(PaymentState.UNKNOWN);
    assertThat(row(payment).statusAttempts()).isZero();
    clock.advance(Duration.ofSeconds(5));
    orchestrator.runOnce();
    assertSettled(payment);
    assertThat(bankCount(payment)).isEqualTo(1);
  }

  @Test
  void serverErrorIsResubmittedUnderSameReference() throws Exception {
    rule("SERVER_ERROR", 0, 1);
    PaymentRow payment = payment(context, 1000);

    orchestrator.runOnce();

    assertThat(row(payment).state()).isEqualTo(PaymentState.UNKNOWN);
    assertThat(bank("GET", "/payments/" + payment.externalReference(), null).statusCode())
        .isEqualTo(404);
    clock.advance(Duration.ofSeconds(5));
    orchestrator.runOnce();
    assertSettled(payment);
    assertThat(row(payment).externalReference()).isEqualTo(payment.externalReference());
    assertThat(bankCount(payment)).isEqualTo(1);
  }

  @Test
  void delayedConfirmationIsFoundLater() throws Exception {
    rule("DELAY", 800, null);
    PaymentRow payment = payment(context, 1000);

    orchestrator.runOnce();

    assertThat(row(payment).state()).isEqualTo(PaymentState.UNKNOWN);
    clock.advance(Duration.ofSeconds(5));
    orchestrator.runOnce();
    assertThat(row(payment).state()).isEqualTo(PaymentState.UNKNOWN);
    assertThat(row(payment).statusAttempts()).isEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        JSON.readTree(
                                bank("GET", "/payments/" + payment.externalReference(), null)
                                    .body())
                            .get("status")
                            .asString())
                    .isEqualTo("CONFIRMED"));
    clock.advance(Duration.ofSeconds(10));
    orchestrator.runOnce();
    assertSettled(payment);
  }

  @Test
  void unresolvedPaymentEndsInManualReview() throws Exception {
    rule("SERVER_ERROR", 0, null);
    PaymentRow payment = payment(context, 1000);
    long clearing = internalBalance("CLEARING");
    Instant start = clock.instant();
    orchestrator.runOnce();

    for (long delay : BACKOFF) {
      clock.advance(Duration.ofMillis(delay));
      orchestrator.runOnce();
    }

    assertThat(row(payment).state()).isEqualTo(PaymentState.MANUAL_REVIEW);
    assertThat(row(payment).statusAttempts()).isEqualTo(8);
    assertThat(row(payment).reason()).isEqualTo("unresolved after 8 status queries");
    assertThat(internalBalance("CLEARING") - clearing).isEqualTo(AMOUNT);
    assertThat(transactions(payment, "EXTERNAL_COMPENSATION")).isZero();
    assertThat(Duration.between(start, clock.instant())).isEqualTo(Duration.ofSeconds(600));
  }

  @Test
  void nextAttemptTimesFollowBackoff() throws Exception {
    rule("SERVER_ERROR", 0, null);
    PaymentRow payment = payment(context, 1000);
    orchestrator.runOnce();

    for (int i = 0; i < BACKOFF.length; i++) {
      PaymentRow current = row(payment);
      assertThat(current.statusAttempts()).isEqualTo(i);
      assertThat(Duration.between(clock.instant(), current.nextAttemptAt()))
          .isEqualTo(Duration.ofMillis(BACKOFF[i]));
      clock.set(current.nextAttemptAt());
      orchestrator.runOnce();
    }
    assertThat(row(payment).state()).isEqualTo(PaymentState.MANUAL_REVIEW);
  }

  @Test
  void referenceConflictGoesToManualReview() throws Exception {
    PaymentRow payment = payment(context, 1000);
    assertThat(
            bank(
                    "POST",
                    "/payments",
                    JSON.writeValueAsString(
                        Map.of(
                            "reference",
                            payment.externalReference(),
                            "creditor_account",
                            payment.creditorAccount(),
                            "amount",
                            AMOUNT + 1,
                            "currency",
                            payment.currency())))
                .statusCode())
        .isEqualTo(200);

    orchestrator.runOnce();

    assertThat(row(payment).state()).isEqualTo(PaymentState.MANUAL_REVIEW);
    assertThat(row(payment).reason()).isEqualTo("reference conflict");
  }

  @Test
  void confirmTwiceSettlesOnce() throws Exception {
    rule("CONFIRM_TWICE", 0, null);
    PaymentRow payment = payment(context, 1000);

    orchestrator.runOnce();
    orchestrator.runOnce();

    assertSettled(payment);
    assertThat(transactions(payment, "EXTERNAL_SETTLEMENT")).isEqualTo(1);
  }

  @Test
  void resumesFromEachPersistedState() throws Exception {
    for (PaymentState state :
        List.of(PaymentState.HELD, PaymentState.CAPTURED, PaymentState.SUBMITTING)) {
      PaymentRow payment = payment(context, 1000);
      try (Connection db = appJdbc()) {
        store.claim(
            db, UUID.randomUUID().toString(), clock.instant(), clock.instant().plusSeconds(30), 10);
      }
      PaymentRow current = steps.hold(payment).orElseThrow();
      if (state != PaymentState.HELD) {
        current = steps.capture(current).orElseThrow();
      }
      if (state == PaymentState.SUBMITTING) {
        current = steps.markSubmitting(current).orElseThrow();
      }
      assertThat(current.state()).isEqualTo(state);
      clock.advance(Duration.ofSeconds(31));

      orchestrator.runOnce();

      assertSettled(payment);
      assertThat(holdCount(payment)).isEqualTo(1);
      assertThat(transactions(payment, "HOLD_CAPTURE")).isEqualTo(1);
      assertThat(transactions(payment, "EXTERNAL_SETTLEMENT")).isEqualTo(1);
    }
  }

  @Test
  void stepsAreIdempotent() throws Exception {
    PaymentRow payment = payment(context, 1000);
    PaymentRow held = steps.hold(payment).orElseThrow();
    assertThat(steps.hold(payment).orElseThrow().holdId()).isEqualTo(held.holdId());
    assertThat(holdCount(payment)).isEqualTo(1);
    PaymentRow captured = steps.capture(held).orElseThrow();
    assertThat(steps.capture(held).orElseThrow().captureTransactionId())
        .isEqualTo(captured.captureTransactionId());
    assertThat(transactions(payment, "HOLD_CAPTURE")).isEqualTo(1);
    PaymentRow submitting = steps.markSubmitting(captured).orElseThrow();
    assertThat(steps.markSubmitting(captured)).isEmpty();
    assertThat(events(payment)).containsExactly("INITIATED", "HELD", "CAPTURED", "SUBMITTING");
    LocalDate date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    PaymentRow settled = steps.settle(submitting, date).orElseThrow();
    assertThat(steps.settle(submitting, date).orElseThrow().resolutionTransactionId())
        .isEqualTo(settled.resolutionTransactionId());
    assertThat(transactions(payment, "EXTERNAL_SETTLEMENT")).isEqualTo(1);
  }

  @Test
  void staleStatusRescheduleDoesNotChangeSettledPayment() throws Exception {
    PaymentRow payment = payment(context, 1000);
    PaymentRow held = steps.hold(payment).orElseThrow();
    PaymentRow captured = steps.capture(held).orElseThrow();
    PaymentRow submitting = steps.markSubmitting(captured).orElseThrow();
    Instant nextAttemptAt = clock.instant().truncatedTo(ChronoUnit.MICROS).plusSeconds(5);
    PaymentRow unknown = steps.markUnknown(submitting, 1, nextAttemptAt, "timeout").orElseThrow();
    PaymentRow settled =
        steps.settle(unknown, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)).orElseThrow();
    assertThat(settled.state()).isEqualTo(PaymentState.SETTLED);
    clock.advance(Duration.ofSeconds(1));

    assertThat(steps.markUnknown(unknown, 2, nextAttemptAt.plusSeconds(10), "status still unknown"))
        .isEmpty();

    PaymentRow current = row(payment);
    assertThat(current.state()).isEqualTo(PaymentState.SETTLED);
    assertThat(current.statusAttempts()).isEqualTo(settled.statusAttempts());
    assertThat(current.nextAttemptAt()).isEqualTo(settled.nextAttemptAt());
    assertThat(current.updatedAt()).isEqualTo(settled.updatedAt());
  }

  @Test
  void twoOrchestratorsDoNotDoubleProcess() throws Exception {
    List<PaymentRow> payments = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      payments.add(payment(context, 1000));
    }
    PaymentOrchestrator second =
        new PaymentOrchestrator(
            store,
            steps,
            context.getBean(ExtbankClient.class),
            context.getBean(StrategyRunner.class),
            context.getBean(LedgerProperties.class),
            clock);
    CountDownLatch start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      List<Future<?>> runs = new ArrayList<>();
      for (PaymentOrchestrator worker : List.of(orchestrator, second)) {
        runs.add(
            workers.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < 100 && !allTerminal(payments); i++) {
                    worker.runOnce();
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> run : runs) {
        run.get(60, TimeUnit.SECONDS);
      }
    }
    long captures = 0;
    long settlements = 0;
    for (PaymentRow payment : payments) {
      assertSettled(payment);
      captures += transactions(payment, "HOLD_CAPTURE");
      settlements += transactions(payment, "EXTERNAL_SETTLEMENT");
    }
    assertThat(captures).isEqualTo(20);
    assertThat(settlements).isEqualTo(20);
  }

  @Test
  void leaseHidesPaymentFromSecondClaim() throws Exception {
    PaymentRow payment = payment(context, 1000);
    Instant now = clock.instant();
    try (Connection db = appJdbc()) {
      assertThat(store.claim(db, "first", now, now.plusSeconds(30), 10))
          .extracting(PaymentRow::id)
          .contains(payment.id());
      assertThat(store.claim(db, "second", now, now.plusSeconds(30), 10))
          .extracting(PaymentRow::id)
          .doesNotContain(payment.id());
      clock.advance(Duration.ofSeconds(31));
      assertThat(store.claim(db, "second", clock.instant(), clock.instant().plusSeconds(30), 10))
          .extracting(PaymentRow::id)
          .contains(payment.id());
      store.releaseLease(db, payment.id(), "second");
    }
    orchestrator.runOnce();
    assertSettled(payment);
  }

  @Test
  void everyTransitionHasEventAndAuditRow() throws Exception {
    PaymentRow payment = payment(context, 1000);
    orchestrator.runOnce();
    List<String> actions = new ArrayList<>();
    Set<UUID> auditIds = new HashSet<>();
    try (Connection db = appJdbc();
        PreparedStatement query =
            db.prepareStatement(
                "SELECT e.audit_id, a.action, a.resource_type, a.resource_id, a.request_id, a.actor "
                    + "FROM ledger.payment_event e LEFT JOIN ledger.audit_log a ON a.id = e.audit_id "
                    + "WHERE e.payment_id = ? ORDER BY e.id")) {
      query.setObject(1, payment.id());
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          auditIds.add(rows.getObject("audit_id", UUID.class));
          actions.add(rows.getString("action"));
          assertThat(rows.getString("resource_type")).isEqualTo("external_payment");
          assertThat(rows.getString("resource_id")).isEqualTo(payment.id().toString());
          assertThat(rows.getString("request_id")).isEqualTo(payment.requestId());
          if (!"payment.create".equals(rows.getString("action"))) {
            assertThat(rows.getString("actor")).isEqualTo("job:orchestrator");
          }
        }
      }
    }
    assertThat(auditIds).hasSize(5);
    assertThat(actions)
        .containsExactly(
            "payment.create",
            "payment.hold",
            "payment.capture",
            "payment.submit",
            "payment.settle");
  }

  @Test
  void checkerHoldsDuringEveryState() throws Exception {
    PaymentRow payment = payment(context, 1000);
    assertInvariants(payment);
    PaymentRow held = steps.hold(payment).orElseThrow();
    assertInvariants(held);
    PaymentRow captured = steps.capture(held).orElseThrow();
    assertInvariants(captured);
    PaymentRow submitting = steps.markSubmitting(captured).orElseThrow();
    assertInvariants(submitting);
    PaymentRow unknown = steps.markUnknown(submitting, 0, clock.instant(), "timeout").orElseThrow();
    assertInvariants(unknown);
    assertInvariants(
        steps.settle(unknown, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)).orElseThrow());

    PaymentRow rejected =
        steps
            .markSubmitting(
                steps.capture(steps.hold(payment(context, 1000)).orElseThrow()).orElseThrow())
            .orElseThrow();
    assertInvariants(steps.compensate(rejected, "rejected by rule").orElseThrow());
    PaymentRow manual =
        steps
            .markSubmitting(
                steps.capture(steps.hold(payment(context, 1000)).orElseThrow()).orElseThrow())
            .orElseThrow();
    assertInvariants(steps.manualReview(manual, "reference conflict").orElseThrow());
    PaymentRow failed = steps.hold(payment(context, 0)).orElseThrow();
    assertThat(failed.state()).isEqualTo(PaymentState.FAILED);
    assertInvariants(failed);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pessimistic", "optimistic", "serializable"})
  void behavesTheSameOnEveryStrategy(String strategy) throws Exception {
    try (ConfigurableApplicationContext application = ledger(strategy)) {
      PaymentOrchestrator worker = application.getBean(PaymentOrchestrator.class);
      PaymentRow accepted = payment(application, 1000);
      long clearing = internalBalance("CLEARING");
      long settlement = internalBalance("SETTLEMENT");
      worker.runOnce();
      assertSettled(accepted);
      assertThat(accountValue(accepted.sourceAccountId(), "balance")).isEqualTo(1000 - AMOUNT);
      assertThat(accountValue(accepted.sourceAccountId(), "held")).isZero();
      assertThat(internalBalance("CLEARING") - clearing).isZero();
      assertThat(internalBalance("SETTLEMENT") - settlement).isEqualTo(-AMOUNT);
      rule("REJECT", 0, null);
      PaymentRow rejected = payment(application, 1000);
      worker.runOnce();
      assertCompensated(rejected);
      assertThat(internalBalance("CLEARING") - clearing).isZero();
    }
  }

  private ConfigurableApplicationContext ledger(String strategy) {
    return LedgerApplication.builder()
        .initializers(
            initializer ->
                ((GenericApplicationContext) initializer).registerBean(Clock.class, () -> clock))
        .run(
            args(
                Map.of(
                    "tally.ledger.strategy",
                    strategy,
                    "spring.datasource.hikari.maximum-pool-size",
                    "8",
                    "tally.ledger.extbank.url",
                    bankUrl,
                    "tally.ledger.extbank.api-key",
                    API_KEY,
                    "tally.ledger.extbank.timeout-ms",
                    "300")));
  }

  private PaymentRow payment(ConfigurableApplicationContext application, long funding)
      throws Exception {
    JsonNode account = postJson(application, "/v1/accounts", "{\"currency\":\"XTS\"}", 201);
    UUID source = UUID.fromString(account.get("id").asString());
    if (funding > 0) {
      postJson(
          application,
          "/v1/deposits",
          "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"XTS\"}".formatted(source, funding),
          201);
    }
    JsonNode payment =
        postJson(
            application,
            "/v1/external-payments",
            "{\"source_account_id\":\"%s\",\"amount\":%d,\"currency\":\"XTS\",\"creditor_account\":\"DEMO-OK\"}"
                .formatted(source, AMOUNT),
            202);
    try (Connection db = appJdbc()) {
      return application
          .getBean(PaymentStore.class)
          .find(db, UUID.fromString(payment.get("id").asString()))
          .orElseThrow();
    }
  }

  private JsonNode postJson(
      ConfigurableApplicationContext application, String path, String body, int status) {
    HttpResponse<String> response =
        send(
            request(url(application, path), token(Role.OPERATOR))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> bank(String method, String path, String body) {
    return send(
        HttpRequest.newBuilder(URI.create(bankUrl + path))
            .header("X-Api-Key", API_KEY)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)));
  }

  private void rule(String behaviour, long delay, Integer remaining) {
    String body =
        "{\"creditor_account\":\"DEMO-OK\",\"behaviour\":\"%s\",\"delay_ms\":%d,\"remaining\":%s}"
            .formatted(behaviour, delay, remaining);
    assertThat(bank("POST", "/control/rules", body).statusCode()).isEqualTo(201);
  }

  private PaymentRow row(PaymentRow payment) throws Exception {
    try (Connection db = appJdbc()) {
      return store.find(db, payment.id()).orElseThrow();
    }
  }

  private List<String> events(PaymentRow payment) throws Exception {
    try (Connection db = appJdbc()) {
      return store.events(db, payment.id()).stream().map(PaymentStore.EventRow::toState).toList();
    }
  }

  private long transactions(PaymentRow payment, String kind) throws Exception {
    return count(
        "SELECT count(*) FROM ledger.ledger_transaction t JOIN ledger.idempotency_key k "
            + "ON k.id = t.idempotency_key_id WHERE k.client_id = 'job:orchestrator' "
            + "AND k.key LIKE ?"
            + (kind == null ? "" : " AND t.kind = '" + kind + "'"),
        "pay:" + payment.id() + ":%");
  }

  private long holdCount(PaymentRow payment) throws Exception {
    return count(
        "SELECT count(*) FROM ledger.hold h JOIN ledger.idempotency_key k "
            + "ON k.id = h.idempotency_key_id WHERE k.client_id = 'job:orchestrator' AND k.key = ?",
        "pay:" + payment.id() + ":hold");
  }

  private long bankCount(PaymentRow payment) throws Exception {
    return count(
        "SELECT count(*) FROM extbank.payment WHERE reference = ?", payment.externalReference());
  }

  private long count(String sql, Object value) throws Exception {
    try (Connection db = superJdbc();
        PreparedStatement query = db.prepareStatement(sql)) {
      query.setObject(1, value);
      try (ResultSet row = query.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private UUID internalId(String kind) throws Exception {
    try (Connection db = appJdbc();
        PreparedStatement query =
            db.prepareStatement(
                "SELECT id FROM ledger.account WHERE kind = ? AND currency = 'XTS'")) {
      query.setString(1, kind);
      try (ResultSet row = query.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getObject(1, UUID.class);
      }
    }
  }

  private long internalBalance(String kind) throws Exception {
    return accountValue(internalId(kind), "balance");
  }

  private long accountValue(UUID account, String column) throws Exception {
    return count("SELECT " + column + " FROM ledger.account WHERE id = ?", account);
  }

  private void assertSettled(PaymentRow payment) throws Exception {
    assertThat(row(payment).state()).isEqualTo(PaymentState.SETTLED);
    assertThat(row(payment).resolutionTransactionId()).isNotNull();
    assertThat(transactions(payment, "EXTERNAL_SETTLEMENT")).isEqualTo(1);
  }

  private void assertCompensated(PaymentRow payment) throws Exception {
    assertThat(row(payment).state()).isEqualTo(PaymentState.REJECTED_COMPENSATED);
    assertThat(row(payment).reason()).isEqualTo("rejected by rule");
    assertThat(accountValue(payment.sourceAccountId(), "balance")).isEqualTo(1000);
    assertThat(accountValue(payment.sourceAccountId(), "held")).isZero();
    assertThat(transactions(payment, "EXTERNAL_COMPENSATION")).isEqualTo(1);
  }

  private boolean allTerminal(List<PaymentRow> payments) throws Exception {
    for (PaymentRow payment : payments) {
      if (!row(payment).state().terminal()) {
        return false;
      }
    }
    return true;
  }

  private void assertInvariants(PaymentRow payment) throws Exception {
    Set<String> subjects =
        new HashSet<>(
            List.of(
                "payment=" + payment.id(),
                "account=" + payment.sourceAccountId(),
                "account=" + internalId("CLEARING"),
                "account=" + internalId("SETTLEMENT"),
                "currency=XTS",
                "hold=" + payment.holdId(),
                "transaction=" + payment.captureTransactionId(),
                "transaction=" + payment.resolutionTransactionId()));
    try (Connection db = appJdbc()) {
      assertThat(context.getBean(InvariantChecker.class).check(db))
          .noneMatch(violation -> subjects.contains(violation.subject()));
    }
  }
}
