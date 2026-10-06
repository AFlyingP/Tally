package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingTest {

  private static final UUID FIRST = new UUID(0, 1);
  private static final UUID SECOND = new UUID(0, 2);
  private static final UUID THIRD = new UUID(0, 3);

  @Test
  void rejectsSingleLine() {
    assertRuleCode(
        RuleCode.UNBALANCED,
        () -> Posting.of("USD", List.of(new EntryLine(FIRST, Side.DEBIT, 10))));
  }

  @Test
  void rejectsUnequalSides() {
    assertRuleCode(
        RuleCode.UNBALANCED,
        () ->
            Posting.of(
                "USD",
                List.of(
                    new EntryLine(FIRST, Side.DEBIT, 10), new EntryLine(SECOND, Side.CREDIT, 9))));
  }

  @Test
  void rejectsLowercaseCurrency() {
    assertRuleCode(
        RuleCode.INVALID_CURRENCY,
        () ->
            Posting.of(
                "usd",
                List.of(
                    new EntryLine(FIRST, Side.DEBIT, 10), new EntryLine(SECOND, Side.CREDIT, 10))));
  }

  @Test
  void rejectsOverflowingSum() {
    assertRuleCode(
        RuleCode.AMOUNT_OVERFLOW,
        () ->
            Posting.of(
                "USD",
                List.of(
                    new EntryLine(FIRST, Side.DEBIT, Long.MAX_VALUE),
                    new EntryLine(SECOND, Side.DEBIT, Long.MAX_VALUE))));
  }

  @Test
  void moveRejectsSameAccount() {
    assertRuleCode(RuleCode.SAME_ACCOUNT, () -> Posting.move("USD", FIRST, FIRST, 10));
  }

  @Test
  void movePutsDebitLineFirst() {
    Posting posting = Posting.move("USD", FIRST, SECOND, 10);

    assertThat(posting.currency()).isEqualTo("USD");
    assertThat(posting.amount()).isEqualTo(10);
    assertThat(posting.lines())
        .containsExactly(
            new EntryLine(FIRST, Side.DEBIT, 10), new EntryLine(SECOND, Side.CREDIT, 10));
  }

  @Test
  void reversedFlipsEverySideAndKeepsOrder() {
    Posting posting =
        Posting.of(
            "USD",
            List.of(
                new EntryLine(FIRST, Side.DEBIT, 7),
                new EntryLine(SECOND, Side.DEBIT, 3),
                new EntryLine(THIRD, Side.CREDIT, 10)));

    Posting reversed = posting.reversed();

    assertThat(reversed.currency()).isEqualTo("USD");
    assertThat(reversed.amount()).isEqualTo(10);
    assertThat(reversed.lines())
        .containsExactly(
            new EntryLine(FIRST, Side.CREDIT, 7),
            new EntryLine(SECOND, Side.CREDIT, 3),
            new EntryLine(THIRD, Side.DEBIT, 10));
  }

  @Test
  void accountIdsAreSortedAndDistinct() {
    Posting posting =
        Posting.of(
            "USD",
            List.of(
                new EntryLine(THIRD, Side.DEBIT, 4),
                new EntryLine(FIRST, Side.DEBIT, 6),
                new EntryLine(THIRD, Side.CREDIT, 10)));

    assertThat(posting.accountIds()).containsExactly(FIRST, THIRD);
  }

  private static void assertRuleCode(RuleCode code, Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            LedgerRuleException.class, exception -> assertThat(exception.code()).isEqualTo(code));
  }
}
