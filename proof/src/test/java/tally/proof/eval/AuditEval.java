package tally.proof.eval;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import tally.ledger.audit.AuditStore;
import tally.ledger.audit.AuditVerifier;
import tally.platform.security.Role;
import tally.proof.fault.FaultStack;
import tally.proof.harness.Http;
import tools.jackson.databind.json.JsonMapper;

public final class AuditEval {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int REQUESTS_PER_TYPE = 100;

  private AuditEval() {}

  public static Map<String, Object> run(long seed) {
    try (FaultStack stack =
        FaultStack.start(
            "pessimistic",
            EnumSet.of(
                FaultStack.Part.POSTGRES,
                FaultStack.Part.EXTBANK,
                FaultStack.Part.LEDGER,
                FaultStack.Part.WORKER))) {
      String token = stack.issuer().token(Role.ADMIN);
      stack.withEnv(
          "worker", "TALLY_LEDGER_ROLES", "scheduler,orchestrator,relay,expiry,recon,sealer");
      stack.withEnv("worker", "TALLY_FAILPOINT", "");
      stack.start("worker");
      Counts counts = new Counts();
      for (int index = 0; index < REQUESTS_PER_TYPE; index++) {
        runCycle(stack, token, index, counts);
      }
      waitForPending(stack);
      int detected = tamper(stack, seed);
      return Map.of(
          "audit",
          Map.of(
              "changes", counts.changes,
              "rows_per_change_min", counts.minimum,
              "rows_per_change_max", counts.maximum,
              "replays_with_rows", counts.replaysWithRows,
              "tamper_cases", 20,
              "tamper_detected", detected));
    } catch (SQLException e) {
      throw new IllegalStateException("audit evaluation failed", e);
    }
  }

  private static void runCycle(FaultStack stack, String token, int index, Counts counts)
      throws SQLException {
    String account = createAccountId(stack, token, index, "main");
    String destination = createAccountId(stack, token, index, "destination");
    String holdSource = createFundedAccount(stack, token, index, "hold");
    String closeAccount = createAccountId(stack, token, index, "close");
    String freezeAccount = createAccountId(stack, token, index, "freeze");
    String extSource = createFundedAccount(stack, token, index, "external");
    Instant expiresAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
    Http.Response releaseHold =
        Http.post(
            stack.ledgerUrl() + "/v1/holds",
            token,
            holdBody(holdSource, expiresAt),
            "Idempotency-Key",
            "audit-setup-release-" + index,
            "X-Request-Id",
            "audit-setup-release-" + index);
    require2xx(releaseHold, "setup hold");
    String releaseId = releaseHold.json().get("id").asString();

    runOne(
        stack,
        token,
        post("account create", "/v1/accounts", accountBody(), null, index, "account"),
        counts);
    runOne(
        stack,
        token,
        post(
            "deposit",
            "/v1/deposits",
            amount(account, 20_000),
            key(index, "deposit"),
            index,
            "deposit"),
        counts);
    runOne(
        stack,
        token,
        post(
            "withdrawal",
            "/v1/withdrawals",
            amount(account, 1_000),
            key(index, "withdrawal"),
            index,
            "withdrawal"),
        counts);
    Request transfer =
        post(
            "transfer",
            "/v1/transfers",
            transfer(account, destination, 500),
            key(index, "transfer"),
            index,
            "transfer");
    Http.Response transferResponse = runOne(stack, token, transfer, counts);
    String transactionId = transferResponse.json().get("id").asString();
    runOne(
        stack,
        token,
        post(
            "reversal",
            "/v1/transactions/" + transactionId + "/reversal",
            "{}",
            key(index, "reversal"),
            index,
            "reversal"),
        counts);
    runOne(
        stack,
        token,
        post("freeze", "/v1/accounts/" + freezeAccount + "/freeze", "{}", null, index, "freeze"),
        counts);
    runOne(
        stack,
        token,
        post("close", "/v1/accounts/" + closeAccount + "/close", "{}", null, index, "close"),
        counts);
    Request holdPlace =
        post(
            "hold place",
            "/v1/holds",
            holdBody(holdSource, expiresAt),
            key(index, "hold-place"),
            index,
            "hold-place");
    Http.Response holdResponse = runOne(stack, token, holdPlace, counts);
    String holdId = holdResponse.json().get("id").asString();
    runOne(
        stack,
        token,
        post(
            "hold capture",
            "/v1/holds/" + holdId + "/capture",
            JSON.writeValueAsString(Map.of("amount", 100, "destination_account_id", destination)),
            key(index, "hold-capture"),
            index,
            "hold-capture"),
        counts);
    runOne(
        stack,
        token,
        post(
            "hold release",
            "/v1/holds/" + releaseId + "/release",
            "{}",
            key(index, "hold-release"),
            index,
            "hold-release"),
        counts);
    Instant startAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
    Request schedule =
        post(
            "schedule create",
            "/v1/schedules",
            JSON.writeValueAsString(
                Map.of(
                    "source_account_id",
                    holdSource,
                    "destination_account_id",
                    destination,
                    "amount",
                    100,
                    "currency",
                    "EUR",
                    "frequency",
                    "ONCE",
                    "start_at",
                    startAt.toString())),
            key(index, "schedule"),
            index,
            "schedule");
    String scheduleId = runOne(stack, token, schedule, counts).json().get("id").asString();
    runOne(
        stack,
        token,
        post(
            "schedule cancel",
            "/v1/schedules/" + scheduleId + "/cancel",
            "{}",
            null,
            index,
            "schedule-cancel"),
        counts);
    runOne(
        stack,
        token,
        post(
            "external payment create",
            "/v1/external-payments",
            JSON.writeValueAsString(
                Map.of(
                    "source_account_id",
                    extSource,
                    "amount",
                    100,
                    "currency",
                    "EUR",
                    "creditor_account",
                    "AUDIT-" + index)),
            key(index, "payment"),
            index,
            "payment"),
        counts);
  }

  private static String holdBody(String account, Instant expiresAt) {
    return JSON.writeValueAsString(
        Map.of(
            "account_id",
            account,
            "amount",
            100,
            "currency",
            "EUR",
            "expires_at",
            expiresAt.toString()));
  }

  private static Http.Response runOne(
      FaultStack stack, String token, Request request, Counts counts) throws SQLException {
    String requestId = request.requestId();
    Http.Response response = send(stack, token, request, requestId);
    require2xx(response, request.type());
    int rows = auditCount(stack, requestId);
    counts.changes++;
    counts.minimum = Math.min(counts.minimum, rows);
    counts.maximum = Math.max(counts.maximum, rows);
    if (request.key() != null) {
      Http.Response replay = send(stack, token, request, requestId);
      require2xx(replay, request.type() + " replay");
      if (auditCount(stack, requestId) > rows) {
        counts.replaysWithRows++;
      }
    }
    return response;
  }

  private static String createAccountId(FaultStack stack, String token, int index, String suffix) {
    Http.Response response = Http.post(stack.ledgerUrl() + "/v1/accounts", token, accountBody());
    require2xx(response, "setup account");
    return response.json().get("id").asString();
  }

  private static String createFundedAccount(
      FaultStack stack, String token, int index, String suffix) {
    String id = createAccountId(stack, token, index, suffix);
    Http.Response response =
        Http.post(
            stack.ledgerUrl() + "/v1/deposits",
            token,
            amount(id, 50_000),
            "Idempotency-Key",
            "audit-setup-" + index + "-" + suffix,
            "X-Request-Id",
            "audit-setup-" + index + "-" + suffix);
    require2xx(response, "setup deposit");
    return id;
  }

  private static String accountBody() {
    return JSON.writeValueAsString(
        Map.of("currency", "EUR", "min_balance", 0, "normal_side", "CREDIT"));
  }

  private static String amount(String account, long amount) {
    return JSON.writeValueAsString(
        Map.of("account_id", account, "amount", amount, "currency", "EUR"));
  }

  private static String transfer(String source, String destination, long amount) {
    return JSON.writeValueAsString(
        Map.of(
            "source_account_id",
            source,
            "destination_account_id",
            destination,
            "amount",
            amount,
            "currency",
            "EUR"));
  }

  private static Request post(
      String type, String path, String body, String key, int index, String suffix) {
    return new Request(type, path, body, key, "audit-" + index + "-" + suffix);
  }

  private static String key(int index, String suffix) {
    return "audit-key-" + index + "-" + suffix;
  }

  private static Http.Response send(
      FaultStack stack, String token, Request request, String requestId) {
    if (request.key() == null) {
      return Http.post(
          stack.ledgerUrl() + request.path(), token, request.body(), "X-Request-Id", requestId);
    }
    return Http.post(
        stack.ledgerUrl() + request.path(),
        token,
        request.body(),
        "Idempotency-Key",
        request.key(),
        "X-Request-Id",
        requestId);
  }

  private static void require2xx(Http.Response response, String action) {
    if (response.status() < 200 || response.status() >= 300) {
      throw new IllegalStateException(
          action + " returned " + response.status() + ": " + response.body());
    }
  }

  private static int auditCount(FaultStack stack, String requestId) throws SQLException {
    try (Connection connection = stack.jdbc();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT count(*) FROM ledger.audit_log WHERE request_id = ? AND outcome = 'SUCCESS'")) {
      query.setString(1, requestId);
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static void waitForPending(FaultStack stack) {
    long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
    AuditVerifier verifier = new AuditVerifier(new AuditStore());
    while (System.nanoTime() < deadline) {
      try (Connection connection = stack.superuserJdbc()) {
        if (verifier.verify(connection).pendingRows() == 0) {
          return;
        }
      } catch (SQLException e) {
        throw new IllegalStateException("failed to verify audit chain", e);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("audit wait interrupted", e);
      }
    }
    throw new IllegalStateException("audit rows did not seal within 120 seconds");
  }

  private static int tamper(FaultStack stack, long seed) throws SQLException {
    SplittableRandom random = new SplittableRandom(seed);
    int detected = 0;
    for (String kind : List.of("actor", "outcome", "row_hash", "prev_hash", "delete")) {
      for (int index = 0; index < 4; index++) {
        waitForPending(stack);
        List<Sealed> rows = sealedRows(stack);
        int bound = kind.equals("delete") ? rows.size() - 1 : rows.size();
        Sealed row = rows.get(random.nextInt(bound));
        mutate(stack, kind, row);
        AuditVerifier.Report report = verify(stack);
        if (report.brokenSeq() != null && report.brokenSeq() <= row.seq() + 1) {
          detected++;
        }
        restore(stack, kind, row);
        AuditVerifier.Report restored = verify(stack);
        if (restored.brokenSeq() != null || restored.pendingRows() != 0) {
          throw new IllegalStateException("audit verification failed after restore");
        }
      }
    }
    return detected;
  }

  private static List<Sealed> sealedRows(FaultStack stack) throws SQLException {
    String sql =
        "SELECT c.seq,c.audit_id,c.prev_hash,c.chain_hash,c.sealed_at,a.actor,a.outcome,a.row_hash "
            + "FROM ledger.audit_chain c JOIN ledger.audit_log a ON a.id=c.audit_id ORDER BY c.seq";
    try (Connection connection = stack.superuserJdbc();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      List<Sealed> result = new ArrayList<>();
      while (rows.next()) {
        result.add(
            new Sealed(
                rows.getLong("seq"),
                rows.getObject("audit_id", UUID.class),
                rows.getBytes("prev_hash"),
                rows.getBytes("chain_hash"),
                rows.getObject("sealed_at"),
                rows.getString("actor"),
                rows.getString("outcome"),
                rows.getBytes("row_hash")));
      }
      return result;
    }
  }

  private static void mutate(FaultStack stack, String kind, Sealed row) throws SQLException {
    TamperTarget target = target(kind, row);
    try (Connection connection = stack.superuserJdbc();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "ALTER TABLE ledger." + target.table() + " DISABLE TRIGGER " + target.trigger());
      try {
        if (kind.equals("delete")) {
          try (PreparedStatement delete =
              connection.prepareStatement(
                  "DELETE FROM ledger." + target.table() + " WHERE " + target.key() + "=?")) {
            delete.setObject(1, target.keyValue());
            delete.executeUpdate();
          }
        } else {
          try (PreparedStatement update =
              connection.prepareStatement(
                  "UPDATE ledger."
                      + target.table()
                      + " SET "
                      + target.column()
                      + "=? WHERE "
                      + target.key()
                      + "=?")) {
            update.setObject(1, target.changedValue());
            update.setObject(2, target.keyValue());
            update.executeUpdate();
          }
        }
      } finally {
        statement.execute(
            "ALTER TABLE ledger." + target.table() + " ENABLE TRIGGER " + target.trigger());
      }
    }
  }

  private static void restore(FaultStack stack, String kind, Sealed row) throws SQLException {
    TamperTarget target = target(kind, row);
    try (Connection connection = stack.superuserJdbc();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "ALTER TABLE ledger." + target.table() + " DISABLE TRIGGER " + target.trigger());
      try {
        if (kind.equals("delete")) {
          try (PreparedStatement insert =
              connection.prepareStatement(
                  "INSERT INTO ledger.audit_chain(seq,audit_id,prev_hash,chain_hash,sealed_at) VALUES(?,?,?,?,?)")) {
            insert.setLong(1, row.seq());
            insert.setObject(2, row.auditId());
            insert.setBytes(3, row.prevHash());
            insert.setBytes(4, row.chainHash());
            insert.setObject(5, row.sealedAt());
            insert.executeUpdate();
          }
        } else {
          try (PreparedStatement update =
              connection.prepareStatement(
                  "UPDATE ledger."
                      + target.table()
                      + " SET "
                      + target.column()
                      + "=? WHERE "
                      + target.key()
                      + "=?")) {
            update.setObject(1, target.originalValue());
            update.setObject(2, target.keyValue());
            update.executeUpdate();
          }
        }
      } finally {
        statement.execute(
            "ALTER TABLE ledger." + target.table() + " ENABLE TRIGGER " + target.trigger());
      }
    }
  }

  private static TamperTarget target(String kind, Sealed row) {
    return switch (kind) {
      case "actor" ->
          new TamperTarget(
              "audit_log",
              "actor",
              "audit_log_no_update",
              "id",
              row.auditId(),
              row.actor(),
              row.actor() + "x");
      case "outcome" ->
          new TamperTarget(
              "audit_log",
              "outcome",
              "audit_log_no_update",
              "id",
              row.auditId(),
              row.outcome(),
              row.outcome().equals("SUCCESS") ? "DENIED" : "SUCCESS");
      case "row_hash" ->
          new TamperTarget(
              "audit_log",
              "row_hash",
              "audit_log_no_update",
              "id",
              row.auditId(),
              row.rowHash(),
              changedHash(row.rowHash()));
      case "prev_hash" ->
          new TamperTarget(
              "audit_chain",
              "prev_hash",
              "audit_chain_no_update",
              "audit_id",
              row.auditId(),
              row.prevHash(),
              changedHash(row.prevHash()));
      case "delete" ->
          new TamperTarget(
              "audit_chain", null, "audit_chain_no_update", "seq", row.seq(), null, null);
      default -> throw new IllegalArgumentException("unknown tamper kind");
    };
  }

  private static AuditVerifier.Report verify(FaultStack stack) throws SQLException {
    try (Connection connection = stack.superuserJdbc()) {
      return new AuditVerifier(new AuditStore()).verify(connection);
    }
  }

  private static byte[] changedHash(byte[] original) {
    byte[] changed = original.clone();
    changed[0] ^= 1;
    return changed;
  }

  private record Request(String type, String path, String body, String key, String requestId) {}

  private record TamperTarget(
      String table,
      String column,
      String trigger,
      String key,
      Object keyValue,
      Object originalValue,
      Object changedValue) {}

  private static final class Counts {
    private int changes;
    private int minimum = Integer.MAX_VALUE;
    private int maximum;
    private int replaysWithRows;
  }

  private record Sealed(
      long seq,
      UUID auditId,
      byte[] prevHash,
      byte[] chainHash,
      Object sealedAt,
      String actor,
      String outcome,
      byte[] rowHash) {}
}
