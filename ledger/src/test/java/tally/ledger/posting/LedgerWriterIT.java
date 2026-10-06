package tally.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tally.ledger.accounts.AccountService;
import tally.ledger.api.AccountDto;
import tally.ledger.audit.AuditEntry;
import tally.ledger.core.AccountState;
import tally.ledger.core.EntryLine;
import tally.ledger.core.LedgerRuleException;
import tally.ledger.core.Posting;
import tally.ledger.core.RuleCode;
import tally.ledger.core.Side;
import tally.ledger.core.TransactionKind;
import tally.ledger.idempotency.IdempotencyStore;
import tally.ledger.outbox.TransactionEvent;
import tally.ledger.posting.PostedTransaction.PostedEntry;
import tally.ledger.store.AccountStore;
import tally.ledger.testing.LedgerIT;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.id.UuidV7;
import tally.platform.security.Caller;
import tally.platform.security.Role;

class LedgerWriterIT extends LedgerIT {

  private static final Caller CALLER =
      new Caller("test-operator", Role.OPERATOR.wireName(), Role.OPERATOR.scopes());

  private StrategyRunner runner;
  private LedgerWriter writer;
  private IdempotencyStore keys;
  private Clock clock;

  @BeforeEach
  void setUp() {
    runner = app().getBean(StrategyRunner.class);
    writer = app().getBean(LedgerWriter.class);
    keys = app().getBean(IdempotencyStore.class);
    clock = app().getBean(Clock.class);
  }

  @Test
  void depositPostsTwoEntriesAndUpdatesBothBalances() throws Exception {
    UUID customer = create(0);
    UUID settlement = settlement();
    Map<UUID, AccountState> before = accounts(customer, settlement);

    PostedTransaction result = deposit(customer, 500);

    Map<UUID, AccountState> after = accounts(customer, settlement);
    assertThat(after.get(customer).balance()).isEqualTo(500);
    assertThat(after.get(settlement).balance()).isEqualTo(before.get(settlement).balance() + 500);
    for (UUID id : before.keySet()) {
      assertThat(after.get(id).version()).isEqualTo(before.get(id).version() + 1);
      assertThat(after.get(id).held()).isEqualTo(before.get(id).held());
    }
    List<PostedEntry> entries = entries(result.id());
    assertThat(entries).hasSize(2).isEqualTo(result.entries());
    assertThat(entries.get(0).accountId()).isEqualTo(customer);
    assertThat(entries.get(0).side()).isEqualTo(Side.CREDIT);
    assertThat(entries.get(0).amount()).isEqualTo(500);
    assertThat(entries.get(1).accountId()).isEqualTo(settlement);
    assertThat(entries.get(1).side()).isEqualTo(Side.DEBIT);
    assertThat(entries.get(1).amount()).isEqualTo(500);
    assertThat(result.id().version()).isEqualTo(7);
    assertThat(result.kind()).isEqualTo(TransactionKind.DEPOSIT);
    assertThat(result.currency()).isEqualTo("EUR");
    assertThat(result.amount()).isEqualTo(500);
    assertThat(result.postedAt().getNano() % 1000).isZero();
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT * FROM ledger.ledger_transaction WHERE id = ?")) {
      select.setObject(1, result.id());
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString("client_id")).isEqualTo(CALLER.clientId());
        assertThat(row.getString("kind")).isEqualTo("DEPOSIT");
        assertThat(row.getString("currency")).isEqualTo("EUR");
        assertThat(row.getLong("amount")).isEqualTo(500);
        assertThat(row.getString("description")).isEqualTo(result.description());
        assertThat(row.getObject("reverses_id")).isNull();
        assertThat(row.getObject("posted_at", OffsetDateTime.class).toInstant())
            .isEqualTo(result.postedAt());
        assertThat(row.next()).isFalse();
      }
    }
  }

  @Test
  void transferMovesMoneyBetweenCustomers() throws Exception {
    UUID source = create(0);
    UUID destination = create(0);
    deposit(source, 500);

    transfer(source, destination, 200);

    Map<UUID, AccountState> after = accounts(source, destination);
    assertThat(after.get(source).balance()).isEqualTo(300);
    assertThat(after.get(destination).balance()).isEqualTo(200);
  }

  @Test
  void insufficientFundsWritesNothing() throws Exception {
    UUID source = create(0);
    UUID destination = create(0);
    deposit(source, 500);
    RowCounts before = rowCounts();
    Map<UUID, AccountState> balances = accounts(source, destination);

    assertThatThrownBy(() -> transfer(source, destination, 501))
        .isInstanceOfSatisfying(
            LedgerRuleException.class,
            e -> assertThat(e.code()).isEqualTo(RuleCode.INSUFFICIENT_FUNDS));

    assertThat(rowCounts()).isEqualTo(before);
    assertThat(accounts(source, destination)).isEqualTo(balances);
  }

  @Test
  void negativeLimitAllowsOverdraftDownToLimit() throws Exception {
    UUID customer = create(-500);
    UUID settlement = settlement();
    post(TransactionKind.WITHDRAWAL, Posting.move("EUR", customer, settlement, 500));
    assertThat(accounts(customer).get(customer).balance()).isEqualTo(-500);
    RowCounts before = rowCounts();
    Map<UUID, AccountState> balances = accounts(customer, settlement);

    assertThatThrownBy(
            () -> post(TransactionKind.WITHDRAWAL, Posting.move("EUR", customer, settlement, 1)))
        .isInstanceOfSatisfying(
            LedgerRuleException.class,
            e -> assertThat(e.code()).isEqualTo(RuleCode.INSUFFICIENT_FUNDS));

    assertThat(rowCounts()).isEqualTo(before);
    assertThat(accounts(customer, settlement)).isEqualTo(balances);
  }

  @Test
  void missingAccountIsRejected() throws Exception {
    UUID source = create(0);
    UUID missing = UuidV7.next(clock);
    RowCounts before = rowCounts();

    assertThatThrownBy(() -> transfer(source, missing, 1))
        .isInstanceOfSatisfying(
            LedgerRuleException.class,
            e -> assertThat(e.code()).isEqualTo(RuleCode.ACCOUNT_NOT_FOUND));

    assertThat(rowCounts()).isEqualTo(before);
  }

  @Test
  void frozenAccountRejectsTransfer() throws Exception {
    UUID source = create(0);
    UUID destination = create(0);
    deposit(source, 500);
    try (Connection db = appJdbc();
        PreparedStatement update =
            db.prepareStatement(
                "UPDATE ledger.account SET status = 'FROZEN', version = version + 1 WHERE id = ?")) {
      update.setObject(1, source);
      assertThat(update.executeUpdate()).isEqualTo(1);
    }
    RowCounts before = rowCounts();
    Map<UUID, AccountState> balances = accounts(source, destination);

    assertThatThrownBy(() -> transfer(source, destination, 1))
        .isInstanceOfSatisfying(
            LedgerRuleException.class,
            e -> assertThat(e.code()).isEqualTo(RuleCode.ACCOUNT_FROZEN));

    assertThat(rowCounts()).isEqualTo(before);
    assertThat(accounts(source, destination)).isEqualTo(balances);
  }

  @Test
  void overflowIsRejectedAndNothingPosted() throws Exception {
    UUID customer = create(0);
    UUID settlement = settlement();
    try (Connection db = appJdbc();
        PreparedStatement update =
            db.prepareStatement(
                "UPDATE ledger.account SET balance = 9223372036854775800 WHERE id = ?")) {
      update.setObject(1, customer);
      assertThat(update.executeUpdate()).isEqualTo(1);
    }
    RowCounts before = rowCounts();
    Map<UUID, AccountState> balances = accounts(customer, settlement);

    assertThatThrownBy(() -> deposit(customer, 100))
        .isInstanceOfSatisfying(
            LedgerRuleException.class,
            e -> assertThat(e.code()).isEqualTo(RuleCode.AMOUNT_OVERFLOW));

    assertThat(rowCounts()).isEqualTo(before);
    assertThat(accounts(customer, settlement)).isEqualTo(balances);
  }

  @Test
  void writesOneOutboxRowPerTransaction() throws Exception {
    UUID customer = create(0);
    PostedTransaction result = deposit(customer, 500);
    Map<UUID, AccountState> accounts = accounts(customer, settlement());

    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT * FROM ledger.outbox WHERE transaction_id = ?")) {
      select.setObject(1, result.id());
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        UUID eventId = row.getObject("event_id", UUID.class);
        assertThat(eventId.version()).isEqualTo(7);
        assertThat(row.getObject("transaction_id", UUID.class)).isEqualTo(result.id());
        assertThat(row.getObject("key_account_id", UUID.class))
            .isEqualTo(result.entries().getFirst().accountId());
        assertThat(row.getObject("published_at")).isNull();
        assertThat(row.getObject("created_at", OffsetDateTime.class).toInstant())
            .isEqualTo(result.postedAt());
        String requestId = row.getString("request_id");
        assertThat(row.getString("payload"))
            .isEqualTo(TransactionEvent.of(result, eventId, requestId, accounts).toJson());
        assertThat(row.next()).isFalse();
        try (PreparedStatement transaction =
            db.prepareStatement("SELECT request_id FROM ledger.ledger_transaction WHERE id = ?")) {
          transaction.setObject(1, result.id());
          try (ResultSet parent = transaction.executeQuery()) {
            assertThat(parent.next()).isTrue();
            assertThat(parent.getString("request_id")).isEqualTo(requestId);
          }
        }
      }
    }
  }

  @Test
  void writesOneAuditRowReferencingTheTransaction() throws Exception {
    UUID customer = create(0);
    PostedTransaction result = deposit(customer, 500);

    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement("SELECT * FROM ledger.audit_log WHERE transaction_id = ?")) {
      select.setObject(1, result.id());
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getObject("id", UUID.class)).isEqualTo(result.auditId());
        assertThat(row.getObject("transaction_id", UUID.class)).isEqualTo(result.id());
        assertThat(row.getString("actor")).isEqualTo(CALLER.clientId());
        assertThat(row.getString("actor_role")).isEqualTo(CALLER.role());
        assertThat(row.getString("action")).isEqualTo("deposit.post");
        assertThat(row.getString("resource_type")).isEqualTo("account");
        assertThat(row.getString("resource_id")).isEqualTo(customer.toString());
        assertThat(row.getString("outcome")).isEqualTo("SUCCESS");
        assertThat(row.next()).isFalse();
      }
    }
  }

  @Test
  void storedBalanceEqualsSumOfEntriesAfterConcurrentTransfers() throws Exception {
    List<UUID> customers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      UUID customer = create(0);
      customers.add(customer);
      deposit(customer, 1000);
    }
    List<Callable<Void>> tasks = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      int seed = i;
      tasks.add(
          () -> {
            SplittableRandom random = new SplittableRandom(seed);
            for (int j = 0; j < 50; j++) {
              int source = random.nextInt(4);
              int destination = (source + random.nextInt(1, 4)) % 4;
              transfer(customers.get(source), customers.get(destination), random.nextLong(1, 11));
            }
            return null;
          });
    }

    runConcurrently(tasks, 120);

    Map<UUID, AccountState> after = accounts(customers.toArray(UUID[]::new));
    long total = 0;
    for (UUID customer : customers) {
      long balance = after.get(customer).balance();
      assertThat(balance).isEqualTo(entryBalance(customer));
      total += balance;
    }
    assertThat(total).isEqualTo(4000);
  }

  @Test
  void oppositeTransfersDoNotDeadlock() throws Exception {
    UUID a = create(0);
    UUID b = create(0);
    deposit(a, 1000);
    deposit(b, 1000);
    AtomicInteger completed = new AtomicInteger();
    List<Callable<Void>> tasks = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      UUID source = i == 0 ? a : b;
      UUID destination = i == 0 ? b : a;
      tasks.add(
          () -> {
            for (int j = 0; j < 200; j++) {
              transfer(source, destination, 1);
              completed.incrementAndGet();
            }
            return null;
          });
    }

    runConcurrently(tasks, 60);

    assertThat(completed.get()).isEqualTo(400);
    Map<UUID, AccountState> after = accounts(a, b);
    assertThat(after.get(a).balance()).isEqualTo(1000);
    assertThat(after.get(b).balance()).isEqualTo(1000);
  }

  @Test
  void lockTimeoutIsRetriedThenExhausted() throws Exception {
    UUID source = create(0);
    UUID destination = create(0);
    deposit(source, 500);
    try (ConfigurableApplicationContext context =
            startApp(
                Map.of(
                    "tally.ledger.lock-wait-ms", "200", "tally.ledger.retry.max-attempts", "2"));
        Connection lock = appJdbc()) {
      StrategyRunner shortRunner = context.getBean(StrategyRunner.class);
      LedgerWriter shortWriter = context.getBean(LedgerWriter.class);
      IdempotencyStore shortKeys = context.getBean(IdempotencyStore.class);
      Clock shortClock = context.getBean(Clock.class);
      Posting posting = Posting.move("EUR", source, destination, 1);
      String key = UuidV7.next(shortClock).toString();
      AtomicInteger attempts = new AtomicInteger();
      RowCounts before = rowCounts();
      Map<UUID, AccountState> balances = accounts(source, destination);
      lock.setAutoCommit(false);
      try {
        try (PreparedStatement select =
            lock.prepareStatement(
                """
                SELECT id, kind, currency, normal_side, min_balance, status, balance, held, version
                FROM ledger.account WHERE id = ? FOR UPDATE
                """)) {
          select.setObject(1, source);
          try (ResultSet row = select.executeQuery()) {
            assertThat(row.next()).isTrue();
          }
        }

        assertThatThrownBy(
                () ->
                    shortRunner.execute(
                        tx -> {
                          attempts.incrementAndGet();
                          long keyId =
                              shortKeys
                                  .claim(
                                      tx.connection(),
                                      CALLER.clientId(),
                                      key,
                                      new byte[32],
                                      shortClock.instant())
                                  .orElseThrow();
                          return shortWriter.post(
                              tx, command(TransactionKind.TRANSFER, posting, keyId, key));
                        }))
            .isInstanceOfSatisfying(
                ApiException.class,
                e -> {
                  assertThat(e.code()).isEqualTo(ErrorCode.RETRY_EXHAUSTED);
                  assertThat(e.retryAfterSeconds()).isEqualTo(1);
                });

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(rowCounts()).isEqualTo(before);
        assertThat(accounts(source, destination)).isEqualTo(balances);
      } finally {
        lock.rollback();
      }
    }
  }

  @Test
  void claimedKeyIsFoundCompletedAndNotClaimedTwice() {
    String key = UuidV7.next(clock).toString();
    byte[] requestHash = new byte[32];
    for (int i = 0; i < requestHash.length; i++) {
      requestHash[i] = (byte) (i + 1);
    }

    long id =
        runner
            .execute(
                tx ->
                    keys.claim(
                        tx.connection(), CALLER.clientId(), key, requestHash, clock.instant()))
            .value()
            .orElseThrow();
    assertThat(id).isPositive();

    IdempotencyStore.Stored claimed =
        runner
            .execute(tx -> keys.find(tx.connection(), CALLER.clientId(), key))
            .value()
            .orElseThrow();
    assertThat(claimed.id()).isEqualTo(id);
    assertThat(claimed.requestHash()).isEqualTo(requestHash);
    assertThat(claimed.statusCode()).isZero();
    assertThat(claimed.responseBody()).isEmpty();

    String body = "{\"ok\":true}";
    IdempotencyStore.Stored completed =
        runner
            .execute(
                tx -> {
                  keys.complete(tx.connection(), id, 201, body);
                  return keys.find(tx.connection(), CALLER.clientId(), key).orElseThrow();
                })
            .value();
    assertThat(completed.id()).isEqualTo(id);
    assertThat(completed.requestHash()).isEqualTo(requestHash);
    assertThat(completed.statusCode()).isEqualTo(201);
    assertThat(completed.responseBody()).isEqualTo(body);

    assertThat(
            runner
                .execute(
                    tx ->
                        keys.claim(
                            tx.connection(), CALLER.clientId(), key, requestHash, clock.instant()))
                .value())
        .isEmpty();
    assertThat(
            runner
                .execute(tx -> keys.find(tx.connection(), CALLER.clientId() + "-other", key))
                .value())
        .isEmpty();
  }

  private UUID create(long minBalance) throws SQLException {
    return app()
        .getBean(AccountService.class)
        .create(
            CALLER,
            new AccountDto.CreateAccount("EUR", Side.CREDIT, minBalance),
            UuidV7.next(clock).toString())
        .id();
  }

  private UUID settlement() throws SQLException {
    try (Connection db = appJdbc();
        Statement select = db.createStatement();
        ResultSet row =
            select.executeQuery(
                "SELECT id FROM ledger.account WHERE kind = 'SETTLEMENT' AND currency = 'EUR'")) {
      assertThat(row.next()).isTrue();
      UUID id = row.getObject("id", UUID.class);
      assertThat(row.next()).isFalse();
      return id;
    }
  }

  private Map<UUID, AccountState> accounts(UUID... ids) throws SQLException {
    try (Connection db = appJdbc()) {
      return app().getBean(AccountStore.class).load(db, new TreeSet<>(List.of(ids)), false);
    }
  }

  private PostedTransaction deposit(UUID customer, long amount) throws SQLException {
    Posting posting =
        Posting.of(
            "EUR",
            List.of(
                new EntryLine(customer, Side.CREDIT, amount),
                new EntryLine(settlement(), Side.DEBIT, amount)));
    return post(TransactionKind.DEPOSIT, posting);
  }

  private PostedTransaction transfer(UUID source, UUID destination, long amount) {
    return post(TransactionKind.TRANSFER, Posting.move("EUR", source, destination, amount));
  }

  private PostedTransaction post(TransactionKind kind, Posting posting) {
    String key = UuidV7.next(clock).toString();
    return runner
        .execute(
            tx -> {
              long keyId =
                  keys.claim(tx.connection(), CALLER.clientId(), key, new byte[32], clock.instant())
                      .orElseThrow();
              return writer.post(tx, command(kind, posting, keyId, key));
            })
        .value();
  }

  private static PostingCommand command(
      TransactionKind kind, Posting posting, long keyId, String requestId) {
    AuditEntry audit =
        AuditEntry.success(
            CALLER,
            kind.name().toLowerCase(java.util.Locale.ROOT) + ".post",
            "account",
            posting.lines().getFirst().accountId().toString(),
            requestId);
    return new PostingCommand(
        kind, posting, keyId, null, "test posting", Map.of(), audit, CALLER, requestId);
  }

  private List<PostedEntry> entries(UUID transactionId) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                "SELECT id, account_id, side, amount FROM ledger.entry"
                    + " WHERE transaction_id = ? ORDER BY id")) {
      select.setObject(1, transactionId);
      List<PostedEntry> entries = new ArrayList<>();
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          entries.add(
              new PostedEntry(
                  rows.getLong("id"),
                  rows.getObject("account_id", UUID.class),
                  Side.valueOf(rows.getString("side")),
                  rows.getLong("amount")));
        }
      }
      return entries;
    }
  }

  private long entryBalance(UUID accountId) throws SQLException {
    try (Connection db = appJdbc();
        PreparedStatement select =
            db.prepareStatement(
                """
                SELECT coalesce(sum(CASE WHEN e.side = a.normal_side THEN e.amount
                  ELSE -e.amount END), 0)
                FROM ledger.account a LEFT JOIN ledger.entry e ON e.account_id = a.id
                WHERE a.id = ? GROUP BY a.id
                """)) {
      select.setObject(1, accountId);
      try (ResultSet row = select.executeQuery()) {
        assertThat(row.next()).isTrue();
        return row.getLong(1);
      }
    }
  }

  private RowCounts rowCounts() throws SQLException {
    try (Connection db = appJdbc();
        Statement select = db.createStatement();
        ResultSet row =
            select.executeQuery(
                """
                SELECT (SELECT count(*) FROM ledger.ledger_transaction),
                  (SELECT count(*) FROM ledger.entry), (SELECT count(*) FROM ledger.outbox),
                  (SELECT count(*) FROM ledger.audit_log), (SELECT count(*) FROM ledger.idempotency_key)
                """)) {
      assertThat(row.next()).isTrue();
      return new RowCounts(
          row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4), row.getLong(5));
    }
  }

  private static void runConcurrently(List<Callable<Void>> tasks, long timeoutSeconds)
      throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
    CountDownLatch ready = new CountDownLatch(tasks.size());
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Void>> futures = new ArrayList<>();
    try {
      for (Callable<Void> task : tasks) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                  return task.call();
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
      start.countDown();
      for (Future<Void> future : futures) {
        future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private record RowCounts(long transactions, long entries, long outbox, long audit, long keys) {}
}
