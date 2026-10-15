package tally.ledger.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tally.ledger.config.LedgerProperties;
import tally.ledger.core.AuditHash;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.databind.json.JsonMapper;

class AuditChainIT extends LedgerIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuditSealer sealer;

  @BeforeEach
  void sealExistingRows() throws SQLException {
    sealer = newSealer();
    sealAll();
    assertHealthy();
  }

  @AfterEach
  void releaseLock() {
    sealer.close();
  }

  @Test
  void migrationQueuesExistingRows() throws SQLException {
    UUID account = account();
    try (Connection connection = appJdbc();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                """
            SELECT count(*) FROM ledger.audit_log a
            WHERE NOT EXISTS (SELECT 1 FROM ledger.audit_pending p WHERE p.audit_id = a.id)
              AND NOT EXISTS (SELECT 1 FROM ledger.audit_chain c WHERE c.audit_id = a.id)
            """)) {
      assertThat(result.next()).isTrue();
      assertThat(result.getLong(1)).isZero();
    }
    assertThat(count("SELECT count(*) FROM ledger.audit_log"))
        .isEqualTo(
            count("SELECT count(*) FROM ledger.audit_pending")
                + count("SELECT count(*) FROM ledger.audit_chain"));
    assertThat(pending(auditId(account))).isEqualTo(1);
  }

  @Test
  void sealerChainsPendingRows() throws SQLException {
    AuditStore.ChainHead head;
    try (Connection connection = appJdbc()) {
      head =
          app()
              .getBean(AuditStore.class)
              .head(connection)
              .orElseGet(() -> new AuditStore.ChainHead(0, new byte[32]));
    }
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      ids.add(auditId(account()));
    }
    assertThat(sealer.name()).isEqualTo("sealer");
    assertThat(sealer.role()).isEqualTo("sealer");
    assertThat(sealer.runOnce()).isEqualTo(5);
    assertThat(sealer.pollMs()).isEqualTo(app().getBean(LedgerProperties.class).sealer().pollMs());

    long previousSeq = head.seq();
    byte[] previousHash = head.chainHash();
    for (Link link : links(ids)) {
      assertThat(link.seq()).isEqualTo(previousSeq + 1);
      assertThat(link.previous()).containsExactly(previousHash);
      assertThat(link.hash()).containsExactly(AuditHash.chain(previousHash, link.rowHash()));
      assertThat(pending(link.auditId())).isZero();
      previousSeq = link.seq();
      previousHash = link.hash();
    }
    if (head.seq() == 0) {
      assertThat(links(ids).getFirst().previous()).containsExactly(new byte[32]);
    }
    assertThat(sealer.runOnce()).isZero();
    assertHealthy();
  }

  @Test
  void verifyPassesOnUntouchedChain() throws SQLException {
    account();
    sealAll();
    CommandResult result = command();
    assertThat(result.exit()).isZero();
    assertThat(result.out())
        .containsExactly(
            "audit chain ok: " + count("SELECT count(*) FROM ledger.audit_chain") + " rows");
    assertThat(result.err()).isEmpty();
    assertHealthy();
  }

  @Test
  void verifyDetectsChangedRowContent() throws SQLException {
    tamperColumn("audit_log", "actor", "x", "row content changed");
  }

  @Test
  void verifyDetectsChangedRowHash() throws SQLException {
    tamperColumn("audit_log", "row_hash", new byte[32], "row content changed");
  }

  @Test
  void verifyDetectsChangedPrevHash() throws SQLException {
    byte[] changed = new byte[32];
    changed[0] = 1;
    tamperColumn("audit_chain", "prev_hash", changed, "previous hash mismatch");
  }

  @Test
  void verifyDetectsChangedChainHash() throws SQLException {
    tamperColumn("audit_chain", "chain_hash", new byte[32], "chain hash mismatch");
  }

  @Test
  void verifyDetectsDeletedChainRow() throws SQLException {
    List<UUID> ids = List.of(auditId(account()), auditId(account()), auditId(account()));
    sealAll();
    List<Link> rows = links(ids);
    Link deleted = rows.get(1);
    try (Connection connection = superJdbc()) {
      try {
        mutate(
            connection,
            "audit_chain",
            "DELETE FROM ledger.audit_chain WHERE audit_id = ?",
            deleted.auditId());
        assertBroken(rows.get(2).seq(), "gap");
      } finally {
        app()
            .getBean(AuditStore.class)
            .appendChain(
                connection,
                deleted.seq(),
                deleted.auditId(),
                deleted.previous(),
                deleted.hash(),
                deleted.sealedAt().toInstant());
      }
    }
    assertHealthy();
  }

  @Test
  void verifyDetectsDeletedAuditRow() throws SQLException {
    Link deleted = sealedRow();
    try (Connection connection = superJdbc();
        Statement statement = connection.createStatement()) {
      try (PreparedStatement snapshot =
          connection.prepareStatement(
              "CREATE TEMP TABLE saved_audit AS SELECT * FROM ledger.audit_log WHERE id = ?")) {
        snapshot.setObject(1, deleted.auditId());
        snapshot.executeUpdate();
      }
      statement.execute("ALTER TABLE ledger.audit_chain DROP CONSTRAINT audit_chain_audit_id_fkey");
      try {
        mutate(
            connection,
            "audit_log",
            "DELETE FROM ledger.audit_log WHERE id = ?",
            deleted.auditId());
        assertBroken(deleted.seq(), "missing audit row");
      } finally {
        try {
          statement.executeUpdate(
              """
              INSERT INTO ledger.audit_log SELECT * FROM saved_audit
              ON CONFLICT (id) DO NOTHING
              """);
          try (PreparedStatement pending =
              connection.prepareStatement("DELETE FROM ledger.audit_pending WHERE audit_id = ?")) {
            pending.setObject(1, deleted.auditId());
            pending.executeUpdate();
          }
        } finally {
          statement.execute(
              """
              ALTER TABLE ledger.audit_chain ADD CONSTRAINT audit_chain_audit_id_fkey
              FOREIGN KEY (audit_id) REFERENCES ledger.audit_log (id)
              """);
        }
      }
    }
    assertHealthy();
  }

  @Test
  void pendingRowsAreReportedAndRequireSealedFails() throws SQLException {
    UUID id = auditId(account());
    try {
      assertThat(pending(id)).isEqualTo(1);
      assertThat(count("SELECT count(*) FROM ledger.audit_pending")).isEqualTo(1);
      CommandResult result = command();
      assertThat(result.exit()).isZero();
      assertThat(result.err()).isEmpty();
      assertThat(result.out())
          .containsExactly(
              "audit chain ok: " + count("SELECT count(*) FROM ledger.audit_chain") + " rows",
              "pending: 1");
      CommandResult required = command("--require-sealed");
      assertThat(required.exit()).isEqualTo(1);
      assertThat(required.out()).containsExactly("audit chain incomplete: 1 rows pending");
      assertThat(required.err()).isEmpty();
    } finally {
      sealAll();
    }
    assertHealthy();
  }

  @Test
  void chainTablesAreAppendOnly() throws SQLException {
    Link row = sealedRow();
    List<String> mutations =
        List.of(
            "UPDATE ledger.audit_chain SET sealed_at = sealed_at WHERE audit_id = ?",
            "DELETE FROM ledger.audit_chain WHERE audit_id = ?");
    try (Connection application = appJdbc();
        Connection superuser = superJdbc()) {
      for (String sql : mutations) {
        assertSqlState(application, sql, row.auditId(), "42501");
        assertSqlState(superuser, sql, row.auditId(), "TL001");
      }
      for (Connection connection : List.of(application, superuser)) {
        try (Statement statement = connection.createStatement()) {
          assertThatThrownBy(() -> statement.execute("TRUNCATE ledger.audit_chain"))
              .isInstanceOfSatisfying(
                  SQLException.class,
                  exception ->
                      assertThat(exception.getSQLState())
                          .isEqualTo(connection == application ? "42501" : "TL001"));
        }
      }
    }
    assertHealthy();
  }

  @Test
  void onlyOneSealerRuns() throws SQLException {
    UUID id = auditId(account());
    try (AuditSealer second = newSealer()) {
      assertThat(second.runOnce()).isZero();
      assertThat(pending(id)).isEqualTo(1);
      assertThat(sealer.runOnce()).isEqualTo(1);
      assertThat(pending(id)).isZero();
      assertThat(links(List.of(id))).hasSize(1);
    }
    assertHealthy();
  }

  @Test
  void concurrentWritersAndSealerLoseNothing() throws Exception {
    List<UUID> sources = new ArrayList<>();
    List<UUID> destinations = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      UUID source = account();
      sources.add(source);
      destinations.add(account());
      postId(
          "/v1/deposits",
          """
          {"account_id":"%s","amount":1000,"currency":"EUR"}
          """
              .formatted(source));
    }
    sealAll();
    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean writing = new AtomicBoolean(true);
    List<UUID> transactions = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(9)) {
      Future<?> sealing =
          executor.submit(
              () -> {
                start.await();
                while (writing.get()) {
                  sealer.runOnce();
                  Thread.sleep(1);
                }
                return null;
              });
      List<Future<List<UUID>>> writers = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        UUID source = sources.get(i);
        UUID destination = destinations.get(i);
        writers.add(
            executor.submit(
                () -> {
                  start.await();
                  List<UUID> posted = new ArrayList<>();
                  for (int transfer = 0; transfer < 50; transfer++) {
                    posted.add(
                        postId(
                            "/v1/transfers",
                            """
                {"source_account_id":"%s","destination_account_id":"%s",
                 "amount":1,"currency":"EUR"}
                """
                                .formatted(source, destination)));
                  }
                  return posted;
                }));
      }
      start.countDown();
      try {
        for (Future<List<UUID>> writer : writers) {
          transactions.addAll(writer.get(120, TimeUnit.SECONDS));
        }
      } finally {
        writing.set(false);
        sealing.get(30, TimeUnit.SECONDS);
      }
    }
    sealAll();
    assertThat(transactions).hasSize(400).doesNotHaveDuplicates();
    try (Connection connection = appJdbc()) {
      var ids = connection.createArrayOf("uuid", transactions.toArray(UUID[]::new));
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
          SELECT count(*) FROM ledger.audit_log a
          JOIN ledger.audit_chain c ON c.audit_id = a.id WHERE a.transaction_id = ANY(?)
          """)) {
        statement.setArray(1, ids);
        try (ResultSet result = statement.executeQuery()) {
          assertThat(result.next()).isTrue();
          assertThat(result.getLong(1)).isEqualTo(400);
        }
      } finally {
        ids.free();
      }
    }
    assertThat(count("SELECT count(*) FROM ledger.audit_chain"))
        .isEqualTo(count("SELECT count(*) FROM ledger.audit_log"));
    assertHealthy();
  }

  @Test
  void unknownFlagExitsTwo() {
    CommandResult result = command("--bogus");
    assertThat(result.exit()).isEqualTo(2);
    assertThat(result.out()).isEmpty();
    assertThat(result.err()).isEqualTo("verify failed: unknown flag --bogus");
  }

  private AuditSealer newSealer() {
    return new AuditSealer(
        app().getBean(DataSource.class),
        app().getBean(AuditStore.class),
        app().getBean(LedgerProperties.class),
        app().getBean(Clock.class));
  }

  private void sealAll() throws SQLException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (sealer.runOnce() > 0) {
      assertThat(System.nanoTime()).isLessThan(deadline);
    }
    assertThat(count("SELECT count(*) FROM ledger.audit_pending")).isZero();
  }

  private void assertHealthy() throws SQLException {
    AuditVerifier.Report report = verify();
    assertThat(report.brokenSeq()).isNull();
    assertThat(report.brokenReason()).isNull();
    assertThat(report.sealedRows()).isEqualTo(count("SELECT count(*) FROM ledger.audit_chain"));
  }

  private AuditVerifier.Report verify() throws SQLException {
    try (Connection connection = appJdbc()) {
      return app().getBean(AuditVerifier.class).verify(connection);
    }
  }

  private void assertBroken(long seq, String reason) throws SQLException {
    AuditVerifier.Report report = verify();
    assertThat(report.brokenSeq()).isEqualTo(seq);
    assertThat(report.brokenReason()).isEqualTo(reason);
    assertThat(report.sealedRows()).isEqualTo(seq - (reason.equals("gap") ? 2 : 1));
    CommandResult result = command();
    assertThat(result.exit()).isEqualTo(1);
    assertThat(result.out()).containsExactly("audit chain broken at seq " + seq + ": " + reason);
    assertThat(result.err()).isEmpty();
  }

  private CommandResult command(String... flags) {
    ByteArrayOutputStream standardOut = new ByteArrayOutputStream();
    ByteArrayOutputStream standardErr = new ByteArrayOutputStream();
    int exit;
    try (PrintStream out = new PrintStream(standardOut, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(standardErr, true, StandardCharsets.UTF_8)) {
      exit = app().getBean(VerifyCommand.class).run(flags, out, err);
    }
    return new CommandResult(
        exit,
        standardOut.toString(StandardCharsets.UTF_8).lines().toList(),
        standardErr.toString(StandardCharsets.UTF_8).trim());
  }

  private UUID account() {
    return postId("/v1/accounts", "{\"currency\":\"EUR\"}");
  }

  private UUID postId(String path, String body) {
    var response =
        post(path, token(Role.OPERATOR), body, "Idempotency-Key", UUID.randomUUID().toString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
  }

  private UUID auditId(UUID account) throws SQLException {
    try (Connection connection = appJdbc();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM ledger.audit_log WHERE resource_type = 'account' AND resource_id = ?")) {
      statement.setString(1, account.toString());
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        UUID id = result.getObject(1, UUID.class);
        assertThat(result.next()).isFalse();
        return id;
      }
    }
  }

  private long pending(UUID id) throws SQLException {
    try (Connection connection = appJdbc();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM ledger.audit_pending WHERE audit_id = ?")) {
      statement.setObject(1, id);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private long count(String sql) throws SQLException {
    try (Connection connection = appJdbc();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private Link sealedRow() throws SQLException {
    UUID id = auditId(account());
    sealAll();
    return links(List.of(id)).getFirst();
  }

  private List<Link> links(List<UUID> ids) throws SQLException {
    try (Connection connection = appJdbc()) {
      var array = connection.createArrayOf("uuid", ids.toArray(UUID[]::new));
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
          SELECT c.*, a.row_hash FROM ledger.audit_chain c
          JOIN ledger.audit_log a ON a.id = c.audit_id WHERE c.audit_id = ANY(?) ORDER BY c.seq
          """)) {
        statement.setArray(1, array);
        try (ResultSet result = statement.executeQuery()) {
          List<Link> rows = new ArrayList<>();
          while (result.next()) {
            rows.add(
                new Link(
                    result.getLong("seq"),
                    result.getObject("audit_id", UUID.class),
                    result.getBytes("prev_hash"),
                    result.getBytes("chain_hash"),
                    result.getObject("sealed_at", OffsetDateTime.class),
                    result.getBytes("row_hash")));
          }
          assertThat(rows).hasSize(ids.size());
          return rows;
        }
      } finally {
        array.free();
      }
    }
  }

  private void tamperColumn(String table, String column, Object changed, String reason)
      throws SQLException {
    Link row = sealedRow();
    String key = table.equals("audit_log") ? "id" : "audit_id";
    String update = "UPDATE ledger." + table + " SET " + column + " = ? WHERE " + key + " = ?";
    try (Connection connection = superJdbc();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT " + column + " FROM ledger." + table + " WHERE " + key + " = ?")) {
      select.setObject(1, row.auditId());
      Object original;
      try (ResultSet result = select.executeQuery()) {
        assertThat(result.next()).isTrue();
        original = result.getObject(1);
      }
      try {
        mutate(connection, table, update, changed, row.auditId());
        assertBroken(row.seq(), reason);
      } finally {
        mutate(connection, table, update, original, row.auditId());
      }
    }
    assertHealthy();
  }

  private static void mutate(Connection connection, String table, String sql, Object... values)
      throws SQLException {
    try (Statement trigger = connection.createStatement()) {
      trigger.execute("ALTER TABLE ledger." + table + " DISABLE TRIGGER " + table + "_no_update");
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        for (int i = 0; i < values.length; i++) {
          statement.setObject(i + 1, values[i]);
        }
        assertThat(statement.executeUpdate()).isEqualTo(1);
      } finally {
        trigger.execute("ALTER TABLE ledger." + table + " ENABLE TRIGGER " + table + "_no_update");
      }
    }
  }

  private static void assertSqlState(Connection connection, String sql, UUID id, String state)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      assertThatThrownBy(statement::executeUpdate)
          .isInstanceOfSatisfying(
              SQLException.class,
              exception -> assertThat(exception.getSQLState()).isEqualTo(state));
    }
  }

  private record CommandResult(int exit, List<String> out, String err) {}

  private record Link(
      long seq,
      UUID auditId,
      byte[] previous,
      byte[] hash,
      OffsetDateTime sealedAt,
      byte[] rowHash) {}
}
