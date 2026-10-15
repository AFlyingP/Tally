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
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tally.ledger.LedgerApplication;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tally.platform.testing.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduleIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant START = Instant.parse("2026-01-05T10:00:00Z");

  private MutableClock clock;
  private ConfigurableApplicationContext context;

  @BeforeAll
  void start() {
    clock = new MutableClock(START);
    context =
        LedgerApplication.builder()
            .initializers(
                initializer ->
                    ((GenericApplicationContext) initializer)
                        .registerBean(Clock.class, () -> clock))
            .run(args(Map.of()));
  }

  @BeforeEach
  void resetClock() {
    clock.set(START);
  }

  @AfterAll
  void stop() {
    context.close();
  }

  @Test
  void createsOnceScheduleWithFirstOccurrence() throws Exception {
    UUID source = create();
    UUID destination = create();
    Instant startAt = clock.instant().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);

    HttpResponse<String> response =
        createSchedule(source, destination, "ONCE", startAt, null, null, 125, key());

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode schedule = JSON.readTree(response.body());
    UUID scheduleId = UUID.fromString(schedule.get("id").asString());
    assertThat(schedule.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(response.headers().firstValue("Idempotent-Replay")).contains("false");
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT occurrence_date, due_at, state, next_attempt_at FROM"
                    + " ledger.schedule_occurrence WHERE schedule_id = ?")) {
      select.setObject(1, scheduleId);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getObject("occurrence_date", java.time.LocalDate.class))
            .isEqualTo(java.time.LocalDate.parse("2026-01-05"));
        assertThat(row.getObject("due_at", java.time.OffsetDateTime.class).toInstant())
            .isEqualTo(startAt);
        assertThat(row.getString("state")).isEqualTo("PENDING");
        assertThat(row.getObject("next_attempt_at", java.time.OffsetDateTime.class).toInstant())
            .isEqualTo(startAt);
        assertThat(row.next()).isFalse();
      }
    }
    assertThat(getSchedule(scheduleId).get("id").asString()).isEqualTo(scheduleId.toString());
  }

  @Test
  void createsMonthlySchedule() throws Exception {
    UUID source = create();
    UUID destination = create();
    Instant startAt = Instant.parse("2026-01-31T09:00:00Z");

    HttpResponse<String> response =
        createSchedule(source, destination, "MONTHLY", startAt, 31, null, 250, key());

    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("frequency").asString()).isEqualTo("MONTHLY");
    assertThat(body.get("day_of_month").asInt()).isEqualTo(31);
    UUID id = UUID.fromString(body.get("id").asString());
    JsonNode occurrence = occurrences(id, 100).get("items").get(0);
    assertThat(occurrence.get("occurrence_date").asString()).isEqualTo("2026-01-31");
  }

  @Test
  void validationErrors() throws Exception {
    UUID source = create();
    UUID destination = create();
    Instant soon = clock.instant().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
    assertValidation(source, destination, "ONCE", clock.instant().minusSeconds(1), null, null, 10);
    assertValidation(
        source, destination, "ONCE", clock.instant().plus(Duration.ofDays(367)), null, null, 10);
    assertValidation(source, destination, "YEARLY", soon, null, null, 10);
    assertValidation(source, destination, "MONTHLY", soon, null, null, 10);
    assertValidation(source, destination, "DAILY", soon, 5, null, 10);
    assertValidation(
        source, destination, "ONCE", soon, null, java.time.LocalDate.parse("2026-02-01"), 10);
    assertValidation(
        source, destination, "DAILY", soon, null, java.time.LocalDate.parse("2026-01-04"), 10);
    assertValidation(source, destination, "ONCE", soon, null, null, 0);
  }

  @Test
  void accountErrors() throws Exception {
    UUID source = create();
    UUID destination = create();
    Instant soon = clock.instant().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
    assertError(
        createSchedule(source, source, "ONCE", soon, null, null, 100, key()), 422, "SAME_ACCOUNT");
    assertError(
        createSchedule(UUID.randomUUID(), destination, "ONCE", soon, null, null, 100, key()),
        404,
        "NOT_FOUND");
    UUID settlement = internalAccount("SETTLEMENT");
    assertError(
        createSchedule(source, settlement, "ONCE", soon, null, null, 100, key()),
        422,
        "INTERNAL_ACCOUNT");
    freeze(source);
    assertError(
        createSchedule(source, destination, "ONCE", soon, null, null, 100, key()),
        422,
        "ACCOUNT_FROZEN");
    UUID eurSource = create();
    UUID eurDestination = create();
    assertError(
        createSchedule(eurSource, eurDestination, "ONCE", soon, null, null, 100, key(), "USD"),
        422,
        "CURRENCY_MISMATCH");
  }

  @Test
  void createIsIdempotent() throws Exception {
    UUID source = create();
    UUID destination = create();
    String key = key();
    Instant startAt = clock.instant().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);

    HttpResponse<String> first =
        createSchedule(source, destination, "ONCE", startAt, null, null, 100, key);
    HttpResponse<String> replay =
        createSchedule(source, destination, "ONCE", startAt, null, null, 100, key);

    assertThat(first.statusCode()).isEqualTo(201);
    assertThat(replay.statusCode()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
    UUID id = UUID.fromString(JSON.readTree(first.body()).get("id").asString());
    assertThat(scheduleCountForSource(source)).isEqualTo(1);
    assertThat(occurrenceCount(id)).isEqualTo(1);
  }

  @Test
  void cancelSkipsPendingOccurrence() throws Exception {
    UUID source = create();
    UUID destination = create();
    Instant startAt = clock.instant().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
    UUID id =
        scheduleId(createSchedule(source, destination, "ONCE", startAt, null, null, 100, key()));

    HttpResponse<String> response =
        postAt("/v1/schedules/" + id + "/cancel", token(Role.OPERATOR), "");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).get("status").asString()).isEqualTo("CANCELLED");
    assertThat(occurrenceState(id)).isEqualTo("SKIPPED");
    assertThat(occurrenceReason(id)).isEqualTo("CANCELLED");
    assertError(
        postAt("/v1/schedules/" + id + "/cancel", token(Role.OPERATOR), ""),
        422,
        "SCHEDULE_NOT_ACTIVE");
    assertError(
        postAt("/v1/schedules/" + UUID.randomUUID() + "/cancel", token(Role.OPERATOR), ""),
        404,
        "NOT_FOUND");
  }

  @Test
  void occurrencesAreListedNewestFirst() throws Exception {
    UUID source = create();
    UUID destination = create();
    Instant startAt = Instant.parse("2026-01-05T10:30:00Z");
    UUID id =
        scheduleId(createSchedule(source, destination, "DAILY", startAt, null, null, 100, key()));
    insertOccurrence(id, java.time.LocalDate.parse("2026-01-06"), startAt.plus(Duration.ofDays(1)));
    insertOccurrence(id, java.time.LocalDate.parse("2026-01-07"), startAt.plus(Duration.ofDays(2)));

    JsonNode items = occurrences(id, 2).get("items");

    assertThat(items.size()).isEqualTo(2);
    assertThat(items.get(0).get("occurrence_date").asString()).isEqualTo("2026-01-07");
    assertThat(items.get(1).get("occurrence_date").asString()).isEqualTo("2026-01-06");
  }

  private UUID create() throws Exception {
    HttpResponse<String> response =
        postAt("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"EUR\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private void freeze(UUID id) {
    assertThat(postAt("/v1/accounts/" + id + "/freeze", token(Role.ADMIN), "").statusCode())
        .isEqualTo(200);
  }

  private HttpResponse<String> createSchedule(
      UUID source,
      UUID destination,
      String frequency,
      Instant startAt,
      Integer dayOfMonth,
      java.time.LocalDate endDate,
      long amount,
      String key) {
    return createSchedule(
        source, destination, frequency, startAt, dayOfMonth, endDate, amount, key, "EUR");
  }

  private HttpResponse<String> createSchedule(
      UUID source,
      UUID destination,
      String frequency,
      Instant startAt,
      Integer dayOfMonth,
      java.time.LocalDate endDate,
      long amount,
      String key,
      String currency) {
    return postAt(
        "/v1/schedules",
        token(Role.OPERATOR),
        scheduleBody(
            source, destination, frequency, startAt, dayOfMonth, endDate, amount, currency),
        key);
  }

  private void assertValidation(
      UUID source,
      UUID destination,
      String frequency,
      Instant startAt,
      Integer day,
      java.time.LocalDate endDate,
      long amount)
      throws Exception {
    String key = key();
    assertError(
        createSchedule(source, destination, frequency, startAt, day, endDate, amount, key),
        400,
        "VALIDATION_FAILED");
    assertThat(idempotencyKeyCount(key)).isZero();
  }

  private HttpResponse<String> postAt(String path, String token, String body) {
    return postAt(path, token, body, null);
  }

  private HttpResponse<String> postAt(
      String path, String token, String body, String idempotencyKey) {
    var request = request(url(context, path), token).header("Content-Type", "application/json");
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return send(request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)));
  }

  private HttpResponse<String> getAt(String path) {
    return send(request(url(context, path), token(Role.OPERATOR)).GET());
  }

  private JsonNode getSchedule(UUID id) throws Exception {
    HttpResponse<String> response = getAt("/v1/schedules/" + id);
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private JsonNode occurrences(UUID id, int limit) throws Exception {
    HttpResponse<String> response = getAt("/v1/schedules/" + id + "/occurrences?limit=" + limit);
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private UUID internalAccount(String kind) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id FROM ledger.account WHERE kind = ? AND currency = 'EUR'")) {
      select.setString(1, kind);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getObject("id", UUID.class);
      }
    }
  }

  private long scheduleCountForSource(UUID source) throws SQLException {
    return count("SELECT count(*) FROM ledger.schedule WHERE source_account_id = ?", source);
  }

  private long occurrenceCount(UUID id) throws SQLException {
    return count("SELECT count(*) FROM ledger.schedule_occurrence WHERE schedule_id = ?", id);
  }

  private String occurrenceState(UUID id) throws SQLException {
    return occurrenceValue(id, "state");
  }

  private String occurrenceReason(UUID id) throws SQLException {
    return occurrenceValue(id, "reason");
  }

  private String occurrenceValue(UUID id, String column) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT " + column + " FROM ledger.schedule_occurrence WHERE schedule_id = ?")) {
      select.setObject(1, id);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getString(1);
      }
    }
  }

  private long idempotencyKeyCount(String key) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT count(*) FROM ledger.idempotency_key WHERE client_id = 'test-operator'"
                    + " AND key = ?")) {
      select.setString(1, key);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
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

  private void insertOccurrence(UUID scheduleId, java.time.LocalDate date, Instant dueAt)
      throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement insert =
            db.prepareStatement(
                "INSERT INTO ledger.schedule_occurrence"
                    + " (id, schedule_id, occurrence_date, due_at, state, attempts,"
                    + " next_attempt_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?)")) {
      Instant now = clock.instant();
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, scheduleId);
      insert.setObject(3, date);
      insert.setObject(4, dueAt.atOffset(java.time.ZoneOffset.UTC));
      insert.setObject(5, dueAt.atOffset(java.time.ZoneOffset.UTC));
      insert.setObject(6, now.atOffset(java.time.ZoneOffset.UTC));
      insert.executeUpdate();
    }
  }

  private static UUID scheduleId(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private static String scheduleBody(
      UUID source,
      UUID destination,
      String frequency,
      Instant startAt,
      Integer day,
      java.time.LocalDate endDate,
      long amount,
      String currency) {
    return "{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\",\"amount\":%d,\"currency\":\"%s\",\"frequency\":\"%s\",\"start_at\":\"%s\",\"day_of_month\":%s,\"end_date\":%s}"
        .formatted(
            source,
            destination,
            amount,
            currency,
            frequency,
            startAt,
            day == null ? "null" : day,
            endDate == null ? "null" : "\"" + endDate + "\"");
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static void assertError(HttpResponse<String> response, int status, String code)
      throws Exception {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(JSON.readTree(response.body()).get("error").get("code").asString()).isEqualTo(code);
  }
}
