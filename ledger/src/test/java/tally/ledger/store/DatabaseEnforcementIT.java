package tally.ledger.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tally.ledger.testing.LedgerIT;

class DatabaseEnforcementIT extends LedgerIT {

  private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-01-01T00:00:00Z");
  private static final int[] ISOLATIONS = {
    Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_SERIALIZABLE
  };

  @BeforeAll
  static void migrate() {
    app();
  }

  @Test
  void unbalancedTransactionIsRejectedAtCommit() throws SQLException {
    for (int isolation : ISOLATIONS) {
      Rows rows;
      try (Connection db = appJdbc()) {
        db.setTransactionIsolation(isolation);
        db.setAutoCommit(false);
        rows = insertRows(db, "USD");
        insertTransaction(db, rows.transactionId(), rows.keyId(), rows.clientId(), "USD");
        insertEntry(db, rows.transactionId(), rows.debitAccount(), "USD", "DEBIT", 100);
        insertEntry(db, rows.transactionId(), rows.creditAccount(), "USD", "CREDIT", 90);

        assertThatThrownBy(db::commit)
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("TL002"));
        db.rollback();
      }
      assertRolledBack(rows);
    }
  }

  @Test
  void singleEntryTransactionIsRejectedAtCommit() throws SQLException {
    for (int isolation : ISOLATIONS) {
      Rows rows;
      try (Connection db = appJdbc()) {
        db.setTransactionIsolation(isolation);
        db.setAutoCommit(false);
        rows = insertRows(db, "USD");
        insertTransaction(db, rows.transactionId(), rows.keyId(), rows.clientId(), "USD");
        insertEntry(db, rows.transactionId(), rows.debitAccount(), "USD", "DEBIT", 100);

        assertThatThrownBy(db::commit)
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("TL002"));
        db.rollback();
      }
      assertRolledBack(rows);
    }
  }

  @Test
  void balancedTransactionCommits() throws SQLException {
    Rows rows = commitBalanced();

    try (Connection db = appJdbc()) {
      assertThat(
              count(db, "SELECT count(*) FROM ledger.idempotency_key WHERE id = ?", rows.keyId()))
          .isEqualTo(1);
      assertThat(
              count(
                  db,
                  "SELECT count(*) FROM ledger.ledger_transaction WHERE id = ? AND amount = 100",
                  rows.transactionId()))
          .isEqualTo(1);
      assertThat(
              count(
                  db,
                  "SELECT count(*) FROM ledger.entry WHERE transaction_id = ? AND amount = 100",
                  rows.transactionId()))
          .isEqualTo(2);
    }
  }

  @Test
  void entryCurrencyMustMatchTransaction() throws SQLException {
    Rows rows;
    try (Connection db = appJdbc()) {
      db.setAutoCommit(false);
      rows = insertRows(db, "USD");
      UUID euroAccount = UUID.randomUUID();
      insertAccount(db, euroAccount, "EUR");
      insertTransaction(db, rows.transactionId(), rows.keyId(), rows.clientId(), "EUR");
      insertEntry(db, rows.transactionId(), rows.debitAccount(), "USD", "DEBIT", 100);
      insertEntry(db, rows.transactionId(), euroAccount, "EUR", "CREDIT", 100);

      assertThatThrownBy(db::commit)
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("TL002"));
      db.rollback();
    }
    assertRolledBack(rows);
  }

  @Test
  void updateOfEntryIsRejected() throws SQLException {
    commitBalanced();
    assertMutationRejected("UPDATE ledger.entry SET amount = 1");
  }

  @Test
  void deleteOfEntryIsRejected() throws SQLException {
    commitBalanced();
    assertMutationRejected("DELETE FROM ledger.entry");
  }

  @Test
  void updateAndDeleteOfTransactionAreRejected() throws SQLException {
    commitBalanced();
    assertMutationRejected("UPDATE ledger.ledger_transaction SET amount = 1");
    assertMutationRejected("DELETE FROM ledger.ledger_transaction");
  }

  @Test
  void truncateIsRejected() throws SQLException {
    Rows rows = commitBalanced();
    try (Connection db = superJdbc()) {
      assertSqlState(db, "TRUNCATE ledger.entry, ledger.ledger_transaction CASCADE", "TL001");
    }
    try (Connection db = appJdbc()) {
      assertThat(
              count(
                  db,
                  "SELECT count(*) FROM ledger.ledger_transaction WHERE id = ?",
                  rows.transactionId()))
          .isEqualTo(1);
      assertThat(
              count(
                  db,
                  "SELECT count(*) FROM ledger.entry WHERE transaction_id = ?",
                  rows.transactionId()))
          .isEqualTo(2);
    }
  }

  @Test
  void entryCannotJoinAnOlderTransaction() throws SQLException {
    Rows rows = commitBalanced();
    try (Connection db = appJdbc()) {
      db.setAutoCommit(false);
      try (PreparedStatement insert =
          db.prepareStatement(
              """
              INSERT INTO ledger.entry
                (transaction_id, account_id, currency, side, amount, posted_at)
              VALUES (?, ?, 'USD', 'DEBIT', 100, ?), (?, ?, 'USD', 'CREDIT', 100, ?)
              """)) {
        insert.setObject(1, rows.transactionId());
        insert.setObject(2, rows.debitAccount());
        insert.setObject(3, TIME);
        insert.setObject(4, rows.transactionId());
        insert.setObject(5, rows.creditAccount());
        insert.setObject(6, TIME);
        assertThatThrownBy(insert::executeUpdate)
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("TL003"));
      }
      db.rollback();
      assertThat(
              count(
                  db,
                  "SELECT count(*) FROM ledger.entry WHERE transaction_id = ?",
                  rows.transactionId()))
          .isEqualTo(2);
    }
  }

  @Test
  void amountAndCurrencyChecks() throws SQLException {
    try (Connection db = appJdbc()) {
      db.setAutoCommit(false);
      Rows rows = insertRows(db, "USD");
      insertTransaction(db, rows.transactionId(), rows.keyId(), rows.clientId(), "USD");
      assertThatThrownBy(
              () -> insertEntry(db, rows.transactionId(), rows.debitAccount(), "USD", "DEBIT", 0))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
      db.rollback();

      Rows invalidCurrency = insertRows(db, "USD");
      assertThatThrownBy(
              () ->
                  insertTransaction(
                      db,
                      invalidCurrency.transactionId(),
                      invalidCurrency.keyId(),
                      invalidCurrency.clientId(),
                      "eu1"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"));
      db.rollback();
    }
  }

  @Test
  void idempotencyKeyIsUniquePerClient() throws SQLException {
    try (Connection db = appJdbc()) {
      Rows rows = insertRows(db, "USD");
      assertThatThrownBy(() -> insertKey(db, rows.clientId(), rows.key()))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23505"));

      long otherKeyId = insertKey(db, UUID.randomUUID().toString(), rows.key());
      assertThat(otherKeyId).isNotEqualTo(rows.keyId());
      assertThat(count(db, "SELECT count(*) FROM ledger.idempotency_key WHERE key = ?", rows.key()))
          .isEqualTo(2);
    }
  }

  @Test
  void oneTransactionPerKey() throws SQLException {
    Rows rows = commitBalanced();
    try (Connection db = appJdbc()) {
      db.setAutoCommit(false);
      assertThatThrownBy(
              () -> insertTransaction(db, UUID.randomUUID(), rows.keyId(), rows.clientId(), "USD"))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23505"));
      db.rollback();
    }
  }

  @Test
  void balanceBelowLimitIsRejected() throws SQLException {
    try (Connection db = appJdbc()) {
      Rows rows = insertRows(db, "USD");
      String account = " WHERE id = '" + rows.debitAccount() + "'";
      assertSqlState(db, "UPDATE ledger.account SET balance = -1" + account, "23514");
      assertSqlState(db, "UPDATE ledger.account SET held = 1, balance = 0" + account, "23514");
    }
  }

  private Rows commitBalanced() throws SQLException {
    try (Connection db = appJdbc()) {
      db.setAutoCommit(false);
      Rows rows = insertRows(db, "USD");
      insertTransaction(db, rows.transactionId(), rows.keyId(), rows.clientId(), "USD");
      insertEntry(db, rows.transactionId(), rows.debitAccount(), "USD", "DEBIT", 100);
      insertEntry(db, rows.transactionId(), rows.creditAccount(), "USD", "CREDIT", 100);
      db.commit();
      return rows;
    }
  }

  private void assertMutationRejected(String sql) throws SQLException {
    try (Connection db = appJdbc()) {
      assertSqlState(db, sql, "42501");
    }
    try (Connection db = superJdbc()) {
      assertSqlState(db, sql, "TL001");
    }
  }

  private static void assertSqlState(Connection db, String sql, String state) throws SQLException {
    try (Statement statement = db.createStatement()) {
      assertThatThrownBy(() -> statement.executeUpdate(sql))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(state));
    }
  }

  private void assertRolledBack(Rows rows) throws SQLException {
    try (Connection db = appJdbc()) {
      assertThat(
              count(db, "SELECT count(*) FROM ledger.idempotency_key WHERE id = ?", rows.keyId()))
          .isZero();
      assertThat(
              count(
                  db,
                  "SELECT count(*) FROM ledger.ledger_transaction WHERE id = ?",
                  rows.transactionId()))
          .isZero();
      assertThat(
              count(
                  db,
                  "SELECT count(*) FROM ledger.entry WHERE transaction_id = ?",
                  rows.transactionId()))
          .isZero();
    }
  }

  private static long count(Connection db, String sql, Object value) throws SQLException {
    try (PreparedStatement select = db.prepareStatement(sql)) {
      select.setObject(1, value);
      try (ResultSet result = select.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
    }
  }

  private static Rows insertRows(Connection db, String currency) throws SQLException {
    UUID debitAccount = UUID.randomUUID();
    UUID creditAccount = UUID.randomUUID();
    insertAccount(db, debitAccount, currency);
    insertAccount(db, creditAccount, currency);
    String clientId = UUID.randomUUID().toString();
    String key = UUID.randomUUID().toString();
    long keyId = insertKey(db, clientId, key);
    return new Rows(debitAccount, creditAccount, UUID.randomUUID(), clientId, key, keyId);
  }

  private static void insertAccount(Connection db, UUID id, String currency) throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            """
            INSERT INTO ledger.account
              (id, kind, currency, normal_side, min_balance, status, created_at, updated_at)
            VALUES (?, 'CUSTOMER', ?, 'CREDIT', 0, 'OPEN', ?, ?)
            """)) {
      insert.setObject(1, id);
      insert.setString(2, currency);
      insert.setObject(3, TIME);
      insert.setObject(4, TIME);
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }
  }

  private static long insertKey(Connection db, String clientId, String key) throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            """
            INSERT INTO ledger.idempotency_key
              (client_id, key, request_hash, status_code, response_body, created_at)
            VALUES (?, ?, ?, 201, '{}', ?) RETURNING id
            """)) {
      insert.setString(1, clientId);
      insert.setString(2, key);
      insert.setBytes(3, new byte[32]);
      insert.setObject(4, TIME);
      try (ResultSet result = insert.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
    }
  }

  private static void insertTransaction(
      Connection db, UUID id, long keyId, String clientId, String currency) throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            """
            INSERT INTO ledger.ledger_transaction
              (id, kind, currency, amount, idempotency_key_id, client_id, request_id, posted_at)
            VALUES (?, 'TRANSFER', ?, 100, ?, ?, ?, ?)
            """)) {
      insert.setObject(1, id);
      insert.setString(2, currency);
      insert.setLong(3, keyId);
      insert.setString(4, clientId);
      insert.setString(5, id.toString());
      insert.setObject(6, TIME);
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }
  }

  private static void insertEntry(
      Connection db, UUID transactionId, UUID accountId, String currency, String side, long amount)
      throws SQLException {
    try (PreparedStatement insert =
        db.prepareStatement(
            """
            INSERT INTO ledger.entry (transaction_id, account_id, currency, side, amount, posted_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
      insert.setObject(1, transactionId);
      insert.setObject(2, accountId);
      insert.setString(3, currency);
      insert.setString(4, side);
      insert.setLong(5, amount);
      insert.setObject(6, TIME);
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }
  }

  private record Rows(
      UUID debitAccount,
      UUID creditAccount,
      UUID transactionId,
      String clientId,
      String key,
      long keyId) {}
}
