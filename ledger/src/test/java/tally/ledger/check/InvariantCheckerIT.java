package tally.ledger.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tally.ledger.audit.AuditEntry;
import tally.ledger.audit.AuditWriter;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InvariantCheckerIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void cleanDatabaseHasNoViolations() throws Exception {
    UUID source = createAccount();
    UUID destination = createAccount();
    UUID deposit = deposit(source, 1000);
    UUID transfer = transfer(source, destination, 200);
    UUID reversal = reverse(transfer);

    Set<String> subjects =
        Set.of(
            "account=" + source,
            "account=" + destination,
            "transaction=" + deposit,
            "transaction=" + transfer,
            "transaction=" + reversal);
    assertThat(check())
        .noneMatch(
            violation ->
                violation.invariant().equals("I3") || subjects.contains(violation.subject()));
  }

  @Test
  void detectsStoredBalanceDrift() throws Exception {
    UUID account = createAccount();
    long stored = storedBalance(account);
    List<Violation> violations;

    try (Connection db = superJdbc()) {
      setBalance(db, account, stored + 5);
      try {
        violations = check();
      } finally {
        setBalance(db, account, stored);
      }
    }

    List<Violation> found = forSubject(violations, "I4", "account=" + account);
    assertThat(found).hasSize(1);
    assertThat(found.getFirst().detail()).isEqualTo("stored=5 entries=0");
  }

  @Test
  void detectsClearingMismatch() throws Exception {
    createAccount();
    UUID clearing;
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id FROM ledger.account WHERE kind = 'CLEARING' AND currency = 'EUR'");
        ResultSet row = select.executeQuery()) {
      assertThat(row.next()).isTrue();
      clearing = row.getObject("id", UUID.class);
    }
    long stored = storedBalance(clearing);
    List<Violation> violations;
    try (Connection db = superJdbc();
        PreparedStatement update =
            db.prepareStatement("UPDATE ledger.account SET balance = balance + 1 WHERE id = ?")) {
      update.setObject(1, clearing);
      assertThat(update.executeUpdate()).isEqualTo(1);
      try {
        violations = check();
      } finally {
        setBalance(db, clearing, stored);
      }
    }
    assertThat(forSubject(violations, "I9", "currency=EUR"))
        .containsExactly(
            new Violation(
                "I9", "currency=EUR", "clearing=" + (stored + 1) + " unsettled=" + stored));
    assertThat(forSubject(violations, "I4", "account=" + clearing))
        .containsExactly(
            new Violation(
                "I4", "account=" + clearing, "stored=" + (stored + 1) + " entries=" + stored));
  }

  @Test
  void detectsUnbalancedTransaction() throws Exception {
    UUID account = createAccount();
    UUID transaction = deposit(account, 100);
    EntryRow entry = firstEntry(transaction);
    List<Violation> violations;

    try (Connection db = superJdbc()) {
      setTrigger(db, "entry", "entry_no_update", false);
      try {
        setEntryAmount(db, entry.id(), entry.amount() + 1);
        violations = check();
      } finally {
        setEntryAmount(db, entry.id(), entry.amount());
        setTrigger(db, "entry", "entry_no_update", true);
      }
    }

    assertThat(forSubject(violations, "I1", "transaction=" + transaction)).hasSize(1);
    assertThat(forSubject(violations, "I4", "account=" + entry.accountId())).hasSize(1);
  }

  @Test
  void detectsBalanceBelowLimitFromEntries() throws Exception {
    UUID account = createAccount();
    deposit(account, 100);
    UUID withdrawal = withdraw(account, 50);
    EntryRow entry = entryForAccount(withdrawal, account);
    List<Violation> violations;

    try (Connection db = superJdbc()) {
      setTrigger(db, "entry", "entry_no_update", false);
      try {
        setEntryAmount(db, entry.id(), entry.amount() + 150);
        violations = check();
      } finally {
        setEntryAmount(db, entry.id(), entry.amount());
        setTrigger(db, "entry", "entry_no_update", true);
      }
    }

    List<Violation> found = forSubject(violations, "I2", "account=" + account);
    assertThat(found).hasSize(1);
    assertThat(found.getFirst().detail()).isEqualTo("entries=-100 held=0 min=0");
  }

  @Test
  void detectsWrongHeldTotal() throws Exception {
    UUID account = createAccount();
    deposit(account, 100);
    long held = storedHeld(account);
    List<Violation> violations;

    try (Connection db = superJdbc()) {
      setHeld(db, account, held + 1);
      try {
        violations = check();
      } finally {
        setHeld(db, account, held);
      }
    }

    assertThat(forSubject(violations, "I6", "account=" + account))
        .containsExactly(new Violation("I6", "account=" + account, "held=1 active=0"));
  }

  @Test
  void detectsSecondCaptureEvent() throws Exception {
    UUID source = createAccount();
    UUID destination = createAccount();
    deposit(source, 500);
    UUID holdId = placeHold(source, 200);
    captureHold(holdId, 100, destination);
    CapturedHold captured = capturedHold(holdId);
    List<Violation> violations;
    long duplicateEventId = -1;

    try (Connection db = superJdbc();
        Statement statement = db.createStatement()) {
      statement.execute("DROP INDEX ledger.hold_event_final_uq");
      try {
        UUID auditId =
            app()
                .getBean(AuditWriter.class)
                .record(
                    db,
                    new AuditEntry(
                        "checker-test",
                        "operator",
                        "holds:write",
                        "hold.capture",
                        "hold",
                        holdId.toString(),
                        key(),
                        "SUCCESS",
                        null,
                        null));
        duplicateEventId = insertSecondCaptureEvent(db, holdId, captured, auditId);
        violations = check();
      } finally {
        if (duplicateEventId >= 0) {
          statement.execute("ALTER TABLE ledger.hold_event DISABLE TRIGGER hold_event_no_update");
          try (PreparedStatement delete =
              db.prepareStatement("DELETE FROM ledger.hold_event WHERE id = ?")) {
            delete.setLong(1, duplicateEventId);
            assertThat(delete.executeUpdate()).isEqualTo(1);
          } finally {
            statement.execute("ALTER TABLE ledger.hold_event ENABLE TRIGGER hold_event_no_update");
          }
        }
        statement.execute(
            "CREATE UNIQUE INDEX hold_event_final_uq ON ledger.hold_event (hold_id)"
                + " WHERE type <> 'PLACED'");
      }
    }

    assertThat(forSubject(violations, "I7", "hold=" + holdId))
        .containsExactly(new Violation("I7", "hold=" + holdId, "events=2"));
  }

  @Test
  void detectsDisabledTrigger() throws Exception {
    List<Violation> violations;

    try (Connection db = superJdbc()) {
      setTrigger(db, "entry", "entry_no_update", false);
      try {
        violations = check();
      } finally {
        setTrigger(db, "entry", "entry_no_update", true);
      }
    }

    assertThat(forSubject(violations, "I3", "trigger=entry_no_update"))
        .containsExactly(new Violation("I3", "trigger=entry_no_update", "missing"));
  }

  @Test
  void detectsGrantedPrivilege() throws Exception {
    List<Violation> violations;

    try (Connection db = superJdbc();
        Statement statement = db.createStatement()) {
      statement.execute("GRANT UPDATE ON ledger.entry TO ledger_app");
      try {
        violations = check();
      } finally {
        statement.execute("REVOKE UPDATE ON ledger.entry FROM ledger_app");
      }
    }

    assertThat(forSubject(violations, "I3", "privilege=ledger.entry:UPDATE"))
        .containsExactly(new Violation("I3", "privilege=ledger.entry:UPDATE", "granted"));
  }

  @Test
  void detectsMissingOutboxRow() throws Exception {
    UUID transaction = deposit(createAccount(), 100);
    OutboxRow outbox = outboxRow(transaction);
    List<Violation> violations;

    try (Connection db = superJdbc()) {
      deleteOutbox(db, transaction);
      try {
        violations = check();
      } finally {
        restoreOutbox(db, outbox);
      }
    }

    assertThat(forSubject(violations, "I10", "transaction=" + transaction))
        .containsExactly(new Violation("I10", "transaction=" + transaction, "missing event"));
  }

  @Test
  void detectsMissingAuditRow() throws Exception {
    UUID transaction = deposit(createAccount(), 100);
    AuditRow audit = auditRow(transaction);
    List<Violation> violations;

    try (Connection db = superJdbc()) {
      setTrigger(db, "audit_log", "audit_log_no_update", false);
      try {
        deleteAudit(db, audit.id());
        violations = check();
      } finally {
        restoreAudit(db, audit);
        setTrigger(db, "audit_log", "audit_log_no_update", true);
      }
    }

    assertThat(forSubject(violations, "A1", "transaction=" + transaction))
        .containsExactly(new Violation("A1", "transaction=" + transaction, "audit_rows=0"));
  }

  @Test
  void detectsOccurrenceWithoutItsTransaction() throws Exception {
    UUID source = createAccount();
    UUID destination = createAccount();
    Instant startAt =
        java.time.Clock.systemUTC().instant().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
    String body =
        "{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\",\"amount\":10,\"currency\":\"EUR\",\"frequency\":\"ONCE\",\"start_at\":\"%s\"}"
            .formatted(source, destination, startAt);
    var response = post("/v1/schedules", token(Role.OPERATOR), body, "Idempotency-Key", key());
    assertThat(response.statusCode()).isEqualTo(201);
    UUID scheduleId = UUID.fromString(JSON.readTree(response.body()).get("id").asString());
    UUID occurrenceId;
    try (Connection db = superJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id FROM ledger.schedule_occurrence WHERE schedule_id = ?")) {
      select.setObject(1, scheduleId);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        occurrenceId = row.getObject("id", UUID.class);
      }
    }
    List<Violation> violations;
    UUID missingTransaction = UUID.randomUUID();
    try (Connection db = superJdbc();
        Statement statement = db.createStatement()) {
      statement.execute(
          "ALTER TABLE ledger.schedule_occurrence"
              + " DROP CONSTRAINT schedule_occurrence_transaction_id_fkey");
      try {
        try (PreparedStatement update =
            db.prepareStatement(
                "UPDATE ledger.schedule_occurrence SET state = 'POSTED', transaction_id = ?"
                    + " WHERE id = ?")) {
          update.setObject(1, missingTransaction);
          update.setObject(2, occurrenceId);
          assertThat(update.executeUpdate()).isEqualTo(1);
        }
        violations = check();
      } finally {
        try {
          try (PreparedStatement update =
              db.prepareStatement(
                  "UPDATE ledger.schedule_occurrence SET state = 'PENDING', transaction_id = NULL"
                      + " WHERE id = ?")) {
            update.setObject(1, occurrenceId);
            assertThat(update.executeUpdate()).isEqualTo(1);
          }
        } finally {
          statement.execute(
              "ALTER TABLE ledger.schedule_occurrence ADD CONSTRAINT"
                  + " schedule_occurrence_transaction_id_fkey FOREIGN KEY (transaction_id)"
                  + " REFERENCES ledger.ledger_transaction (id)");
        }
      }
    }

    assertThat(forSubject(violations, "I8", "occurrence=" + occurrenceId))
        .containsExactly(new Violation("I8", "occurrence=" + occurrenceId, "missing transaction"));
  }

  @Test
  void commandPrintsViolationsAndExitsOne() throws Exception {
    UUID account = createAccount();
    long stored = storedBalance(account);
    ByteArrayOutputStream standardOut = new ByteArrayOutputStream();
    ByteArrayOutputStream standardErr = new ByteArrayOutputStream();
    int result;

    try (Connection db = superJdbc()) {
      setBalance(db, account, stored + 5);
      try (PrintStream out = new PrintStream(standardOut, true, StandardCharsets.UTF_8);
          PrintStream err = new PrintStream(standardErr, true, StandardCharsets.UTF_8)) {
        try {
          result = app().getBean(CheckCommand.class).run(new String[0], out, err);
        } finally {
          setBalance(db, account, stored);
        }
      }
    }

    assertThat(result).isEqualTo(1);
    assertThat(standardErr.toString(StandardCharsets.UTF_8)).isEmpty();
    List<String> lines = standardOut.toString(StandardCharsets.UTF_8).lines().toList();
    assertThat(lines).anyMatch(line -> line.startsWith("I4 account=" + account + " "));
    assertThat(lines).anyMatch(line -> line.matches("invariants failed: [1-9][0-9]* violations"));
  }

  @Test
  void commandExitsZeroWhenClean() throws Exception {
    try (PostgreSQLContainer postgres = freshPostgres()) {
      postgres.start();
      try (ConfigurableApplicationContext context =
          startApp(Map.of("spring.datasource.url", postgres.getJdbcUrl()))) {
        ByteArrayOutputStream standardOut = new ByteArrayOutputStream();
        ByteArrayOutputStream standardErr = new ByteArrayOutputStream();
        int result;
        try (PrintStream out = new PrintStream(standardOut, true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(standardErr, true, StandardCharsets.UTF_8)) {
          result = context.getBean(CheckCommand.class).run(new String[0], out, err);
        }

        assertThat(result).isZero();
        assertThat(standardOut.toString(StandardCharsets.UTF_8).trim())
            .isEqualTo("invariants ok: 0 violations");
        assertThat(standardErr.toString(StandardCharsets.UTF_8)).isEmpty();
      }
    }
  }

  @Test
  void commandRejectsUnknownFlag() {
    ByteArrayOutputStream standardOut = new ByteArrayOutputStream();
    ByteArrayOutputStream standardErr = new ByteArrayOutputStream();
    int result;

    try (PrintStream out = new PrintStream(standardOut, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(standardErr, true, StandardCharsets.UTF_8)) {
      result = app().getBean(CheckCommand.class).run(new String[] {"--bogus"}, out, err);
    }

    assertThat(result).isEqualTo(2);
    assertThat(standardOut.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(standardErr.toString(StandardCharsets.UTF_8).trim())
        .isEqualTo("check failed: unknown flag --bogus");

    ByteArrayOutputStream failedOut = new ByteArrayOutputStream();
    ByteArrayOutputStream failedErr = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(failedOut, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(failedErr, true, StandardCharsets.UTF_8)) {
      CheckCommand command =
          new CheckCommand(
              new DriverManagerDataSource("jdbc:invalid:ledger", "user", "password"),
              new InvariantChecker());
      assertThat(command.run(new String[0], out, err)).isEqualTo(2);
    }
    assertThat(failedOut.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(failedErr.toString(StandardCharsets.UTF_8)).startsWith("check failed: ");
  }

  private UUID createAccount() throws Exception {
    var response = post("/v1/accounts", token(Role.OPERATOR), "{\"currency\":\"EUR\"}");
    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode body = JSON.readTree(response.body());
    return UUID.fromString(body.get("id").asString());
  }

  private UUID deposit(UUID account, long amount) throws Exception {
    String body =
        "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount);
    var response = post("/v1/deposits", token(Role.OPERATOR), body, "Idempotency-Key", key());
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private UUID withdraw(UUID account, long amount) throws Exception {
    String body =
        "{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"}".formatted(account, amount);
    var response = post("/v1/withdrawals", token(Role.OPERATOR), body, "Idempotency-Key", key());
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private UUID transfer(UUID source, UUID destination, long amount) throws Exception {
    String body =
        ("{\"source_account_id\":\"%s\",\"destination_account_id\":\"%s\","
                + "\"amount\":%d,\"currency\":\"EUR\"}")
            .formatted(source, destination, amount);
    var response = post("/v1/transfers", token(Role.OPERATOR), body, "Idempotency-Key", key());
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private UUID reverse(UUID transaction) throws Exception {
    var response =
        send(
            request(url("/v1/transactions/" + transaction + "/reversal"), token(Role.ADMIN))
                .header("Idempotency-Key", key())
                .POST(HttpRequest.BodyPublishers.noBody()));
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private UUID placeHold(UUID account, long amount) throws Exception {
    String body =
        ("{\"account_id\":\"%s\",\"amount\":%d,\"currency\":\"EUR\"," + "\"expires_at\":\"%s\"}")
            .formatted(
                account, amount, Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS));
    var response = post("/v1/holds", token(Role.OPERATOR), body, "Idempotency-Key", key());
    assertThat(response.statusCode()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private void captureHold(UUID holdId, long amount, UUID destination) throws Exception {
    String body =
        "{\"amount\":%d,\"destination_account_id\":\"%s\"}".formatted(amount, destination);
    var response =
        post(
            "/v1/holds/" + holdId + "/capture",
            token(Role.OPERATOR),
            body,
            "Idempotency-Key",
            key());
    assertThat(response.statusCode()).isEqualTo(201);
  }

  private CapturedHold capturedHold(UUID holdId) throws SQLException {
    try (Connection db = superJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT captured_amount, capture_transaction_id FROM ledger.hold WHERE id = ?")) {
      select.setObject(1, holdId);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return new CapturedHold(
            row.getLong("captured_amount"), row.getObject("capture_transaction_id", UUID.class));
      }
    }
  }

  private static long insertSecondCaptureEvent(
      Connection db, UUID holdId, CapturedHold captured, UUID auditId) throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "INSERT INTO ledger.hold_event (hold_id, type, amount, transaction_id, actor,"
                + " occurred_at, audit_id) VALUES (?, 'CAPTURED', ?, ?, 'checker-test', now(), ?)"
                + " RETURNING id")) {
      insert.setObject(1, holdId);
      insert.setLong(2, captured.amount());
      insert.setObject(3, captured.transactionId());
      insert.setObject(4, auditId);
      try (ResultSet row = insert.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong("id");
      }
    }
  }

  private List<Violation> check() throws SQLException {
    try (Connection db = appJdbc()) {
      return app().getBean(InvariantChecker.class).check(db);
    }
  }

  private static List<Violation> forSubject(
      List<Violation> violations, String invariant, String subject) {
    return violations.stream()
        .filter(violation -> violation.invariant().equals(invariant))
        .filter(violation -> violation.subject().equals(subject))
        .toList();
  }

  private long storedBalance(UUID account) throws SQLException {
    try (Connection db = superJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT balance FROM ledger.account WHERE id = ?")) {
      select.setObject(1, account);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong("balance");
      }
    }
  }

  private long storedHeld(UUID account) throws SQLException {
    try (Connection db = superJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT held FROM ledger.account WHERE id = ?")) {
      select.setObject(1, account);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong("held");
      }
    }
  }

  private static void setBalance(Connection db, UUID account, long balance) throws SQLException {
    try (PreparedStatement update =
        db.prepareStatement("UPDATE ledger.account SET balance = ? WHERE id = ?")) {
      update.setLong(1, balance);
      update.setObject(2, account);
      assertThat(update.executeUpdate()).isEqualTo(1);
    }
  }

  private static void setHeld(Connection db, UUID account, long held) throws SQLException {
    try (PreparedStatement update =
        db.prepareStatement("UPDATE ledger.account SET held = ? WHERE id = ?")) {
      update.setLong(1, held);
      update.setObject(2, account);
      assertThat(update.executeUpdate()).isEqualTo(1);
    }
  }

  private EntryRow firstEntry(UUID transaction) throws SQLException {
    try (Connection db = superJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id, account_id, amount FROM ledger.entry WHERE transaction_id = ? "
                    + "ORDER BY id LIMIT 1")) {
      select.setObject(1, transaction);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return new EntryRow(
            row.getLong("id"), row.getObject("account_id", UUID.class), row.getLong("amount"));
      }
    }
  }

  private EntryRow entryForAccount(UUID transaction, UUID account) throws SQLException {
    try (Connection db = superJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id, account_id, amount FROM ledger.entry "
                    + "WHERE transaction_id = ? AND account_id = ? AND side = 'DEBIT'")) {
      select.setObject(1, transaction);
      select.setObject(2, account);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return new EntryRow(
            row.getLong("id"), row.getObject("account_id", UUID.class), row.getLong("amount"));
      }
    }
  }

  private static void setEntryAmount(Connection db, long id, long amount) throws SQLException {
    try (PreparedStatement update =
        db.prepareStatement("UPDATE ledger.entry SET amount = ? WHERE id = ?")) {
      update.setLong(1, amount);
      update.setLong(2, id);
      assertThat(update.executeUpdate()).isEqualTo(1);
    }
  }

  private static void setTrigger(Connection db, String table, String trigger, boolean enabled)
      throws SQLException {
    String action = enabled ? "ENABLE" : "DISABLE";
    try (Statement statement = db.createStatement()) {
      statement.execute("ALTER TABLE ledger." + table + " " + action + " TRIGGER " + trigger);
    }
  }

  private OutboxRow outboxRow(UUID transaction) throws SQLException {
    try (Connection db = superJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id, event_id, transaction_id, key_account_id, request_id, payload, "
                    + "created_at, published_at FROM ledger.outbox WHERE transaction_id = ?")) {
      select.setObject(1, transaction);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return new OutboxRow(
            row.getLong("id"),
            row.getObject("event_id", UUID.class),
            row.getObject("transaction_id", UUID.class),
            row.getObject("key_account_id", UUID.class),
            row.getString("request_id"),
            row.getString("payload"),
            row.getObject("created_at", OffsetDateTime.class),
            row.getObject("published_at", OffsetDateTime.class));
      }
    }
  }

  private static void deleteOutbox(Connection db, UUID transaction) throws SQLException {
    try (PreparedStatement delete =
        db.prepareStatement("DELETE FROM ledger.outbox WHERE transaction_id = ?")) {
      delete.setObject(1, transaction);
      assertThat(delete.executeUpdate()).isEqualTo(1);
    }
  }

  private static void restoreOutbox(Connection db, OutboxRow row) throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "INSERT INTO ledger.outbox "
                + "(id, event_id, transaction_id, key_account_id, request_id, payload, "
                + "created_at, published_at) OVERRIDING SYSTEM VALUE "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setLong(1, row.id());
      insert.setObject(2, row.eventId());
      insert.setObject(3, row.transactionId());
      insert.setObject(4, row.keyAccountId());
      insert.setString(5, row.requestId());
      insert.setString(6, row.payload());
      insert.setObject(7, row.createdAt());
      insert.setObject(8, row.publishedAt());
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }
  }

  private AuditRow auditRow(UUID transaction) throws SQLException {
    try (Connection db = superJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id, occurred_at, actor, actor_role, scopes, action, resource_type, "
                    + "resource_id, request_id, outcome, reason, transaction_id, row_hash "
                    + "FROM ledger.audit_log WHERE transaction_id = ?")) {
      select.setObject(1, transaction);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return new AuditRow(
            row.getObject("id", UUID.class),
            row.getObject("occurred_at", OffsetDateTime.class),
            row.getString("actor"),
            row.getString("actor_role"),
            row.getString("scopes"),
            row.getString("action"),
            row.getString("resource_type"),
            row.getString("resource_id"),
            row.getString("request_id"),
            row.getString("outcome"),
            row.getString("reason"),
            row.getObject("transaction_id", UUID.class),
            row.getBytes("row_hash"));
      }
    }
  }

  private static void deleteAudit(Connection db, UUID auditId) throws SQLException {
    try (PreparedStatement delete =
        db.prepareStatement("DELETE FROM ledger.audit_log WHERE id = ?")) {
      delete.setObject(1, auditId);
      assertThat(delete.executeUpdate()).isEqualTo(1);
    }
  }

  private static void restoreAudit(Connection db, AuditRow row) throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            "INSERT INTO ledger.audit_log "
                + "(id, occurred_at, actor, actor_role, scopes, action, resource_type, "
                + "resource_id, request_id, outcome, reason, transaction_id, row_hash) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      insert.setObject(1, row.id());
      insert.setObject(2, row.occurredAt());
      insert.setString(3, row.actor());
      insert.setString(4, row.actorRole());
      insert.setString(5, row.scopes());
      insert.setString(6, row.action());
      insert.setString(7, row.resourceType());
      insert.setString(8, row.resourceId());
      insert.setString(9, row.requestId());
      insert.setString(10, row.outcome());
      insert.setString(11, row.reason());
      insert.setObject(12, row.transactionId());
      insert.setBytes(13, row.rowHash());
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }
  }

  private static PostgreSQLContainer freshPostgres() {
    return new PostgreSQLContainer("postgres:16.15-alpine")
        .withDatabaseName("tally")
        .withUsername("postgres")
        .withPassword(PASSWORD)
        .withEnv("LEDGER_OWNER_PASSWORD", PASSWORD)
        .withEnv("LEDGER_APP_PASSWORD", PASSWORD)
        .withEnv("MONITOR_DB_PASSWORD", PASSWORD)
        .withEnv("AUTH_DB_PASSWORD", PASSWORD)
        .withEnv("EXTBANK_DB_PASSWORD", PASSWORD)
        .withCopyFileToContainer(
            MountableFile.forHostPath("../deploy/postgres/init.sh", 0755),
            "/docker-entrypoint-initdb.d/init.sh");
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private record EntryRow(long id, UUID accountId, long amount) {}

  private record CapturedHold(long amount, UUID transactionId) {}

  private record OutboxRow(
      long id,
      UUID eventId,
      UUID transactionId,
      UUID keyAccountId,
      String requestId,
      String payload,
      OffsetDateTime createdAt,
      OffsetDateTime publishedAt) {}

  private record AuditRow(
      UUID id,
      OffsetDateTime occurredAt,
      String actor,
      String actorRole,
      String scopes,
      String action,
      String resourceType,
      String resourceId,
      String requestId,
      String outcome,
      String reason,
      UUID transactionId,
      byte[] rowHash) {}
}
