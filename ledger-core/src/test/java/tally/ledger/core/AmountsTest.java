package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AmountsTest {

  @Test
  void addThrowsOverflowAtLongMax() {
    assertThatThrownBy(() -> Amounts.add(Long.MAX_VALUE, 1))
        .isInstanceOfSatisfying(
            LedgerRuleException.class,
            exception -> assertThat(exception.code()).isEqualTo(RuleCode.AMOUNT_OVERFLOW));
    assertThat(Amounts.add(Long.MAX_VALUE - 1, 1)).isEqualTo(Long.MAX_VALUE);
    assertThat(Amounts.negate(5)).isEqualTo(-5);
    assertThat(Amounts.subtract(5, 7)).isEqualTo(-2);
    assertThatThrownBy(() -> Amounts.subtract(Long.MIN_VALUE, 1))
        .isInstanceOfSatisfying(
            LedgerRuleException.class,
            exception -> assertThat(exception.code()).isEqualTo(RuleCode.AMOUNT_OVERFLOW));
    assertThatThrownBy(() -> Amounts.negate(Long.MIN_VALUE))
        .isInstanceOfSatisfying(
            LedgerRuleException.class,
            exception -> assertThat(exception.code()).isEqualTo(RuleCode.AMOUNT_OVERFLOW));
  }

  @Test
  void requirePositiveRejectsZeroAndNegative() {
    assertThatThrownBy(() -> Amounts.requirePositive(0))
        .isInstanceOfSatisfying(
            LedgerRuleException.class,
            exception -> assertThat(exception.code()).isEqualTo(RuleCode.INVALID_AMOUNT));
    assertThatThrownBy(() -> Amounts.requirePositive(-1))
        .isInstanceOfSatisfying(
            LedgerRuleException.class,
            exception -> assertThat(exception.code()).isEqualTo(RuleCode.INVALID_AMOUNT));
    assertThatThrownBy(() -> new EntryLine(new UUID(0, 1), Side.DEBIT, 0))
        .isInstanceOfSatisfying(
            LedgerRuleException.class,
            exception -> assertThat(exception.code()).isEqualTo(RuleCode.INVALID_AMOUNT));
    assertThatCode(() -> Amounts.requirePositive(1)).doesNotThrowAnyException();
  }
}
