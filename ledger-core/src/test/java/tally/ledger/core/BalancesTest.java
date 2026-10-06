package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BalancesTest {

  private static final UUID ACCOUNT_ID = new UUID(0, 1);

  @Test
  void creditIncreasesCreditNormalAccount() {
    AccountState account = customer(Side.CREDIT, AccountStatus.OPEN, 100, 0, 0);

    assertThat(Balances.apply(account, Side.CREDIT, 40)).isEqualTo(140);
  }

  @Test
  void debitDecreasesCreditNormalAccount() {
    AccountState account = customer(Side.CREDIT, AccountStatus.OPEN, 100, 0, 0);

    assertThat(Balances.apply(account, Side.DEBIT, 40)).isEqualTo(60);
  }

  @Test
  void debitIncreasesDebitNormalAccount() {
    AccountState account = internal(AccountKind.SETTLEMENT, Side.DEBIT, AccountStatus.OPEN, 100);

    assertThat(Balances.apply(account, Side.DEBIT, 40)).isEqualTo(140);
  }

  @Test
  void applyOverflowThrows() {
    AccountState account =
        customer(Side.CREDIT, AccountStatus.OPEN, Long.MAX_VALUE, 0, Long.MIN_VALUE);

    assertRuleCode(RuleCode.AMOUNT_OVERFLOW, () -> Balances.apply(account, Side.CREDIT, 1));
  }

  @Test
  void limitAllowsExactlyMinBalance() {
    AccountState account = customer(Side.CREDIT, AccountStatus.OPEN, 0, 0, -500);

    assertThatCode(() -> Balances.requireWithinLimit(account, -500, 0)).doesNotThrowAnyException();
  }

  @Test
  void limitRejectsOneBelow() {
    AccountState account = customer(Side.CREDIT, AccountStatus.OPEN, 0, 0, -500);

    assertRuleCode(
        RuleCode.INSUFFICIENT_FUNDS, () -> Balances.requireWithinLimit(account, -501, 0));
  }

  @Test
  void limitCountsHeldAmount() {
    AccountState account = customer(Side.CREDIT, AccountStatus.OPEN, 100, 101, 0);

    assertRuleCode(
        RuleCode.INSUFFICIENT_FUNDS,
        () -> Balances.requireWithinLimit(account, account.balance(), account.held()));
  }

  @Test
  void limitIgnoresInternalAccounts() {
    AccountState settlement = internal(AccountKind.SETTLEMENT, Side.DEBIT, AccountStatus.OPEN, 0);
    AccountState clearing = internal(AccountKind.CLEARING, Side.DEBIT, AccountStatus.OPEN, 0);

    assertThatCode(() -> Balances.requireWithinLimit(settlement, Long.MIN_VALUE, 1))
        .doesNotThrowAnyException();
    assertThatCode(() -> Balances.requireWithinLimit(clearing, Long.MIN_VALUE, 1))
        .doesNotThrowAnyException();
  }

  @Test
  void frozenRejectsDepositWithdrawalTransfer() {
    AccountState account = customer(Side.CREDIT, AccountStatus.FROZEN, 0, 0, 0);

    assertRuleCode(
        RuleCode.ACCOUNT_FROZEN,
        () -> Balances.requirePostable(account, TransactionKind.DEPOSIT, "USD"));
    assertRuleCode(
        RuleCode.ACCOUNT_FROZEN,
        () -> Balances.requirePostable(account, TransactionKind.WITHDRAWAL, "USD"));
    assertRuleCode(
        RuleCode.ACCOUNT_FROZEN,
        () -> Balances.requirePostable(account, TransactionKind.TRANSFER, "USD"));
  }

  @Test
  void frozenAllowsReversalCaptureSettlementCompensation() {
    AccountState account = customer(Side.CREDIT, AccountStatus.FROZEN, 0, 0, 0);

    assertThatCode(() -> Balances.requirePostable(account, TransactionKind.REVERSAL, "USD"))
        .doesNotThrowAnyException();
    assertThatCode(() -> Balances.requirePostable(account, TransactionKind.HOLD_CAPTURE, "USD"))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> Balances.requirePostable(account, TransactionKind.EXTERNAL_SETTLEMENT, "USD"))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> Balances.requirePostable(account, TransactionKind.EXTERNAL_COMPENSATION, "USD"))
        .doesNotThrowAnyException();
  }

  @Test
  void closedRejectsAllSevenKinds() {
    AccountState account = customer(Side.CREDIT, AccountStatus.CLOSED, 0, 0, 0);

    for (TransactionKind kind : TransactionKind.values()) {
      assertRuleCode(RuleCode.ACCOUNT_CLOSED, () -> Balances.requirePostable(account, kind, "USD"));
    }
  }

  @Test
  void statusIsCheckedBeforeCurrency() {
    AccountState account = customer(Side.CREDIT, AccountStatus.CLOSED, 0, 0, 0);

    assertRuleCode(
        RuleCode.ACCOUNT_CLOSED,
        () -> Balances.requirePostable(account, TransactionKind.DEPOSIT, "EUR"));
  }

  @Test
  void foreignCurrencyRejected() {
    AccountState account = customer(Side.CREDIT, AccountStatus.OPEN, 0, 0, 0);

    assertRuleCode(
        RuleCode.CURRENCY_MISMATCH,
        () -> Balances.requirePostable(account, TransactionKind.DEPOSIT, "EUR"));
  }

  private static AccountState customer(
      Side normalSide, AccountStatus status, long balance, long held, long minBalance) {
    return new AccountState(
        ACCOUNT_ID, AccountKind.CUSTOMER, "USD", normalSide, minBalance, status, balance, held, 0);
  }

  private static AccountState internal(
      AccountKind kind, Side normalSide, AccountStatus status, long balance) {
    return new AccountState(ACCOUNT_ID, kind, "USD", normalSide, null, status, balance, 0, 0);
  }

  private static void assertRuleCode(RuleCode code, Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            LedgerRuleException.class, exception -> assertThat(exception.code()).isEqualTo(code));
  }
}
