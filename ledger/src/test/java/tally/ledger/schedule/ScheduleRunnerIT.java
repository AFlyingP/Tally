package tally.ledger.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
import tally.ledger.LedgerApplication;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tally.platform.testing.MutableClock;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduleRunnerIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant START = Instant.parse("2025-06-01T10:00:00Z");

  private final List<UUID> schedules = new ArrayList<>();
  private MutableClock clock;
  private ConfigurableApplicationContext context;

  @BeforeAll
  void start() {
    clock = new MutableClock(START);
    context = startRunnerApp(Map.of());
  }

  @BeforeEach
  void resetAndDrain() throws Exception {
    clock.set(START);
    try (Connection db = appJdbc();
        PreparedStatement updateSchedule =
            db.prepareStatement("UPDATE ledger.schedule SET status = 'CANCELLED' WHERE id = ?");
        PreparedStatement updateOccurrence =
            db.prepareStatement(
                "UPDATE ledger.schedule_occurrence SET state = 'SKIPPED', reason = 'CANCELLED'"
                    + " WHERE schedule_id = ? AND state = 'PENDING'")) {
      for (UUID id : schedules) {
        updateSchedule.setObject(1, id);
        updateSchedule.executeUpdate();
        updateOccurrence.setObject(1, id);
        updateOccurrence.executeUpdate();
      }
    }
    schedules.clear();
    while (runner(context).runOnce() > 0) {
      // Drain due work left by a prior test before creating this test's schedules.
    }
  }

  @AfterAll
  void stop() {
    context.close();
  }

  @Test
  void dueOccurrencePostsOneTransfer() throws Exception {
    UUID source = funded(context, 1000);
    UUID destination = account(context);
    UUID schedule = createSchedule(context, source, destination, "ONCE", null, null, 125, null);
    clock.advance(Duration.ofSeconds(2));

    assertThat(runner(context).runOnce()).isEqualTo(1);

    Occurrence occurrence = occurrence(schedule, LocalDate.parse("2025-06-01"));
    assertThat(occurrence.state()).isEqualTo("POSTED");
    assertThat(occurrence.transactionId()).isNotNull();
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT t.kind, t.amount, k.client_id, k.key FROM ledger.ledger_transaction t"
                    + " JOIN ledger.idempotency_key k ON k.id = t.idempotency_key_id WHERE t.id = ?")) {
      select.setObject(1, occurrence.transactionId());
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString("kind")).isEqualTo("TRANSFER");
        assertThat(row.getLong("amount")).isEqualTo(125);
        assertThat(row.getString("client_id")).isEqualTo("job:scheduler");
        assertThat(row.getString("key")).isEqualTo("sched:" + schedule + ":2025-06-01");
      }
    }
    assertThat(scheduleStatus(schedule)).isEqualTo("COMPLETED");
  }

  @Test
  void notDueOccurrenceIsLeftAlone() throws Exception {
    UUID source = funded(context, 1000);
    UUID destination = account(context);
    UUID schedule = createSchedule(context, source, destination, "ONCE", null, null, 20, null);

    assertThat(runner(context).runOnce()).isZero();
    assertThat(occurrence(schedule, LocalDate.parse("2025-06-01")).state()).isEqualTo("PENDING");
  }

  @Test
  void dailyCreatesNextOccurrence() throws Exception {
    UUID source = funded(context, 1000);
    UUID destination = account(context);
    UUID schedule = createSchedule(context, source, destination, "DAILY", null, null, 20, null);
    clock.advance(Duration.ofSeconds(2));

    assertThat(runner(context).runOnce()).isEqualTo(1);
    assertThat(occurrence(schedule, LocalDate.parse("2025-06-02")).state()).isEqualTo("PENDING");
  }

  @Test
  void insufficientFundsRetriesThreeTimesThenFails() throws Exception {
    UUID source = account(context);
    UUID destination = account(context);
    UUID schedule = createSchedule(context, source, destination, "ONCE", null, null, 20, null);
    clock.advance(Duration.ofSeconds(2));

    assertThat(runner(context).runOnce()).isEqualTo(1);
    Occurrence first = occurrence(schedule, LocalDate.parse("2025-06-01"));
    assertThat(first.state()).isEqualTo("PENDING");
    assertThat(first.attempts()).isEqualTo(1);
    assertThat(first.nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofHours(1)));
    for (int attempt = 2; attempt <= 4; attempt++) {
      clock.advance(Duration.ofHours(1));
      assertThat(runner(context).runOnce()).isEqualTo(1);
      Occurrence current = occurrence(schedule, LocalDate.parse("2025-06-01"));
      assertThat(current.attempts()).isEqualTo(attempt);
      if (attempt < 4) {
        assertThat(current.state()).isEqualTo("PENDING");
      } else {
        assertThat(current.state()).isEqualTo("FAILED");
        assertThat(current.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(current.transactionId()).isNull();
      }
    }
    assertThat(schedulerKeyCount(schedule, LocalDate.parse("2025-06-01"))).isZero();
    assertThat(auditCount(schedule, "occurrence.retry")).isEqualTo(3);
    assertThat(auditCount(schedule, "occurrence.fail")).isEqualTo(1);
  }

  @Test
  void retrySucceedsWhenFundsArrive() throws Exception {
    UUID source = account(context);
    UUID destination = account(context);
    UUID schedule = createSchedule(context, source, destination, "ONCE", null, null, 20, null);
    clock.advance(Duration.ofSeconds(2));
    assertThat(runner(context).runOnce()).isEqualTo(1);
    deposit(context, source, 100);
    clock.advance(Duration.ofHours(1));

    assertThat(runner(context).runOnce()).isEqualTo(1);
    assertThat(occurrence(schedule, LocalDate.parse("2025-06-01")).state()).isEqualTo("POSTED");
  }

  @Test
  void frozenAccountFailsWithoutRetry() throws Exception {
    UUID source = funded(context, 1000);
    UUID destination = account(context);
    UUID schedule = createSchedule(context, source, destination, "ONCE", null, null, 20, null);
    assertThat(
            postAt(context, "/v1/accounts/" + source + "/freeze", "", token(Role.ADMIN))
                .statusCode())
        .isEqualTo(200);
    clock.advance(Duration.ofSeconds(2));

    assertThat(runner(context).runOnce()).isEqualTo(1);
    Occurrence occurrence = occurrence(schedule, LocalDate.parse("2025-06-01"));
    assertThat(occurrence.state()).isEqualTo("FAILED");
    assertThat(occurrence.reason()).isEqualTo("ACCOUNT_FROZEN");
    assertThat(occurrence.attempts()).isZero();
  }

  @Test
  void catchUpRunsMissedDaysInDateOrder() throws Exception {
    UUID source = funded(context, 1000);
    UUID destination = account(context);
    UUID schedule = createSchedule(context, source, destination, "DAILY", null, null, 10, null);
    clock.advance(Duration.ofDays(3).plusSeconds(2));

    assertThat(runner(context).runOnce()).isEqualTo(4);
    List<PostedAt> posted = postedAt(schedule);
    assertThat(posted).hasSize(4);
    assertThat(posted.stream().map(PostedAt::postedAt).toList())
        .isEqualTo(posted.stream().map(PostedAt::postedAt).sorted().toList());
    for (int i = 1; i < posted.size(); i++) {
      assertThat(posted.get(i).firstEntryId()).isGreaterThan(posted.get(i - 1).firstEntryId());
    }
    assertThat(occurrence(schedule, LocalDate.parse("2025-06-05")).state()).isEqualTo("PENDING");
  }

  @Test
  void occurrencesOlderThanSevenDaysAreSkipped() throws Exception {
    UUID source = funded(context, 1000);
    UUID destination = account(context);
    UUID schedule = createSchedule(context, source, destination, "DAILY", null, null, 10, null);
    clock.advance(Duration.ofDays(10).plusSeconds(1));

    assertThat(runner(context).runOnce()).isEqualTo(11);
    for (int day = 1; day <= 3; day++) {
      Occurrence skipped = occurrence(schedule, LocalDate.parse("2025-06-%02d".formatted(day)));
      assertThat(skipped.state()).isEqualTo("SKIPPED");
      assertThat(skipped.reason()).isEqualTo("MISSED_WINDOW");
    }
    for (int day = 4; day <= 11; day++) {
      assertThat(occurrence(schedule, LocalDate.parse("2025-06-%02d".formatted(day))).state())
          .isEqualTo("POSTED");
    }
  }

  @Test
  void endDateCompletesSchedule() throws Exception {
    UUID source = funded(context, 1000);
    UUID destination = account(context);
    UUID schedule =
        createSchedule(
            context, source, destination, "DAILY", LocalDate.parse("2025-06-03"), null, 10, null);
    clock.advance(Duration.ofDays(2).plusSeconds(2));

    assertThat(runner(context).runOnce()).isEqualTo(3);
    assertThat(scheduleStatus(schedule)).isEqualTo("COMPLETED");
  }

  @Test
  void twoRunnersPostEachOccurrenceOnce() throws Exception {
    concurrentScenario(context, 50);
  }

  @ParameterizedTest
  @ValueSource(strings = {"pessimistic", "optimistic", "serializable"})
  void runnerBehavesTheSameOnEveryStrategy(String strategy) throws Exception {
    try (ConfigurableApplicationContext app =
        startRunnerApp(Map.of("tally.ledger.strategy", strategy))) {
      concurrentScenario(app, 20);
    }
  }

  private void concurrentScenario(ConfigurableApplicationContext app, int count) throws Exception {
    UUID source = funded(app, count * 2L);
    UUID destination = account(app);
    List<UUID> created = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      created.add(createSchedule(app, source, destination, "ONCE", null, null, 1, null));
    }
    clock.advance(Duration.ofSeconds(2));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<Integer>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 2; i++) {
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                  int total = 0;
                  int handled;
                  do {
                    handled = runner(app).runOnce();
                    total += handled;
                  } while (handled > 0);
                  return total;
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      for (Future<Integer> result : results) {
        result.get(60, TimeUnit.SECONDS);
      }
    } finally {
      go.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
    for (UUID id : created) {
      assertThat(scheduleStatus(id)).isEqualTo("COMPLETED");
      assertThat(occurrenceCount(id)).isEqualTo(1);
      assertThat(transactionCount(id)).isEqualTo(1);
    }
  }

  private ConfigurableApplicationContext startRunnerApp(Map<String, String> overrides) {
    return LedgerApplication.builder()
        .initializers(
            initializer ->
                ((GenericApplicationContext) initializer).registerBean(Clock.class, () -> clock))
        .run(args(withApiRole(overrides)));
  }

  private Map<String, String> withApiRole(Map<String, String> overrides) {
    var settings = new java.util.HashMap<>(overrides);
    settings.put("tally.ledger.roles", "api");
    settings.put("spring.datasource.hikari.maximum-pool-size", "8");
    return settings;
  }

  private ScheduleRunner runner(ConfigurableApplicationContext app) {
    return app.getBean(ScheduleRunner.class);
  }

  private UUID createSchedule(
      ConfigurableApplicationContext app,
      UUID source,
      UUID destination,
      String frequency,
      LocalDate endDate,
      Integer day,
      long amount,
      Instant startAt)
      throws Exception {
    Instant start = startAt == null ? clock.instant().plusSeconds(1) : startAt;
    String body =
        "{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\",\"frequency\":\"%s\",\"start_at\":\"%s\",\"day_of_month\":%s,\"end_date\":%s}"
            .formatted(
                source,
                destination,
                amount,
                frequency,
                start,
                day == null ? "null" : day,
                endDate == null ? "null" : "\"" + endDate + "\"");
    HttpResponse<String> response =
        postAt(app, "/v1/schedules", body, token(Role.OPERATOR), "Idempotency-Key", key());
    assertThat(response.statusCode()).isEqualTo(201);
    UUID id = UUID.fromString(JSON.readTree(response.body()).get("id").asString());
    schedules.add(id);
    return id;
  }

  private UUID account(ConfigurableApplicationContext app) throws Exception {
    HttpResponse<String> response =
        postAt(app, "/v1/accounts", "{\"currency\":\"EUR\"}", token(Role.OPERATOR));
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private UUID funded(ConfigurableApplicationContext app, long amount) throws Exception {
    UUID id = account(app);
    deposit(app, id, amount);
    return id;
  }

  private void deposit(ConfigurableApplicationContext app, UUID id, long amount) {
    String body =
        "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(id, amount);
    HttpResponse<String> response =
        postAt(app, "/v1/deposits", body, token(Role.OPERATOR), "Idempotency-Key", key());
    assertThat(response.statusCode()).isEqualTo(201);
  }

  private HttpResponse<String> postAt(
      ConfigurableApplicationContext app,
      String path,
      String body,
      String bearer,
      String... headers) {
    var request = request(url(app, path), bearer).header("Content-Type", "application/json");
    if (headers.length > 0) {
      request.headers(headers);
    }
    return send(request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)));
  }

  private Occurrence occurrence(UUID schedule, LocalDate date) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id, state, attempts, next_attempt_at, transaction_id, reason"
                    + " FROM ledger.schedule_occurrence WHERE schedule_id = ? AND occurrence_date = ?")) {
      select.setObject(1, schedule);
      select.setObject(2, date);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return new Occurrence(
            row.getObject("id", UUID.class),
            row.getString("state"),
            row.getInt("attempts"),
            row.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
            row.getObject("transaction_id", UUID.class),
            row.getString("reason"));
      }
    }
  }

  private String scheduleStatus(UUID id) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT status FROM ledger.schedule WHERE id = ?")) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getString(1);
      }
    }
  }

  private long occurrenceCount(UUID id) throws SQLException {
    return count("SELECT count(*) FROM ledger.schedule_occurrence WHERE schedule_id = ?", id);
  }

  private long transactionCount(UUID id) throws SQLException {
    return count(
        "SELECT count(t.id) FROM ledger.schedule_occurrence o JOIN ledger.ledger_transaction t"
            + " ON t.id = o.transaction_id WHERE o.schedule_id = ?",
        id);
  }

  private long schedulerKeyCount(UUID schedule, LocalDate date) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.idempotency_key WHERE client_id = 'job:scheduler'"
                    + " AND key = ?")) {
      select.setString(1, "sched:" + schedule + ":" + date);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private long auditCount(UUID schedule, String action) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.audit_log WHERE action = ?"
                    + " AND resource_type = 'schedule_occurrence'"
                    + " AND resource_id IN (SELECT id::text FROM ledger.schedule_occurrence"
                    + " WHERE schedule_id = ?)")) {
      select.setString(1, action);
      select.setObject(2, schedule);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private List<PostedAt> postedAt(UUID schedule) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT o.occurrence_date, t.posted_at,"
                    + " (SELECT min(e.id) FROM ledger.entry e WHERE e.transaction_id = t.id)"
                    + " AS first_entry_id FROM ledger.schedule_occurrence o"
                    + " JOIN ledger.ledger_transaction t ON t.id = o.transaction_id"
                    + " WHERE o.schedule_id = ? ORDER BY o.occurrence_date")) {
      select.setObject(1, schedule);
      try (ResultSet rows = select.executeQuery()) {
        List<PostedAt> values = new ArrayList<>();
        while (rows.next()) {
          values.add(
              new PostedAt(
                  rows.getObject("occurrence_date", LocalDate.class),
                  rows.getObject("posted_at", OffsetDateTime.class).toInstant(),
                  rows.getLong("first_entry_id")));
        }
        return values;
      }
    }
  }

  private long count(String sql, UUID id) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select = db.prepareStatement(sql)) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private record Occurrence(
      UUID id,
      String state,
      int attempts,
      Instant nextAttemptAt,
      UUID transactionId,
      String reason) {}

  private record PostedAt(LocalDate date, Instant postedAt, long firstEntryId) {}
}
