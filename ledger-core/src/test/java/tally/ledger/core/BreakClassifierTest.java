package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tally.ledger.core.BreakClassifier.Break;
import tally.ledger.core.BreakClassifier.LedgerPayment;

class BreakClassifierTest {

  private static final LocalDate DATE = LocalDate.of(2026, 3, 10);
  private static final LocalDate OTHER_DATE = LocalDate.of(2026, 3, 9);
  private static final String REFERENCE = "TLY-0001";

  @Test
  void matchedPaymentHasNoBreak() {
    assertThat(
            BreakClassifier.classify(
                DATE,
                List.of(line(2, REFERENCE, 1200, "EUR", DATE)),
                List.of(payment(REFERENCE, 1200, "EUR", DATE))))
        .isEmpty();
    assertThat(BreakClassifier.classify(DATE, List.of(), List.of())).isEmpty();
  }

  @Test
  void missingInLedger() {
    assertThat(
            BreakClassifier.classify(
                DATE, List.of(line(3, REFERENCE, 1300, "USD", OTHER_DATE)), List.of()))
        .containsExactly(
            new Break(
                BreakType.MISSING_IN_LEDGER,
                REFERENCE,
                3,
                null,
                null,
                null,
                1300L,
                "USD",
                OTHER_DATE));
  }

  @Test
  void missingInStatement() {
    assertThat(
            BreakClassifier.classify(
                DATE, List.of(), List.of(payment(REFERENCE, 1200, "EUR", DATE))))
        .containsExactly(
            new Break(
                BreakType.MISSING_IN_STATEMENT,
                REFERENCE,
                null,
                1200L,
                "EUR",
                DATE,
                null,
                null,
                null));
  }

  @Test
  void amountMismatch() {
    assertComparison(BreakType.AMOUNT_MISMATCH, 1300, "EUR", DATE, 1200, "EUR", DATE);
    assertComparison(BreakType.AMOUNT_MISMATCH, 1199, "EUR", DATE, 1200, "EUR", DATE);
  }

  @Test
  void currencyMismatch() {
    assertComparison(BreakType.CURRENCY_MISMATCH, 1200, "USD", DATE, 1200, "EUR", DATE);
  }

  @Test
  void dateMismatchWhenLineDateDiffersFromLedger() {
    assertComparison(BreakType.DATE_MISMATCH, 1200, "EUR", DATE, 1200, "EUR", OTHER_DATE);
    assertComparison(BreakType.DATE_MISMATCH, 1200, "EUR", OTHER_DATE, 1200, "EUR", DATE);
  }

  @Test
  void dateMismatchWhenLineDateDiffersFromBusinessDate() {
    assertComparison(BreakType.DATE_MISMATCH, 1200, "EUR", OTHER_DATE, 1200, "EUR", OTHER_DATE);
  }

  @Test
  void duplicateInStatementIsTheOnlyBreakForThatReference() {
    StatementLine first = line(2, REFERENCE, 1300, "USD", OTHER_DATE);
    StatementLine second = line(4, REFERENCE, 1400, "GBP", DATE);
    LedgerPayment payment = payment(REFERENCE, 1200, "EUR", DATE);
    Break expected =
        new Break(
            BreakType.DUPLICATE_IN_STATEMENT,
            REFERENCE,
            2,
            1200L,
            "EUR",
            DATE,
            1300L,
            "USD",
            OTHER_DATE);
    assertThat(BreakClassifier.classify(DATE, List.of(first, second), List.of(payment)))
        .containsExactly(expected);
    assertThat(BreakClassifier.classify(DATE, List.of(first, second, second), List.of(payment)))
        .containsExactly(expected);
    assertThat(BreakClassifier.classify(DATE, List.of(first, second), List.of()))
        .containsExactly(
            new Break(
                BreakType.DUPLICATE_IN_STATEMENT,
                REFERENCE,
                2,
                null,
                null,
                null,
                1300L,
                "USD",
                OTHER_DATE));
  }

  @Test
  void currencyBeatsAmountBeatsDate() {
    assertComparison(BreakType.CURRENCY_MISMATCH, 1300, "USD", OTHER_DATE, 1200, "EUR", DATE);
    assertComparison(BreakType.AMOUNT_MISMATCH, 1300, "EUR", OTHER_DATE, 1200, "EUR", DATE);
  }

  @Test
  void settledOnAnotherDateAndAbsentIsNotABreak() {
    assertThat(
            BreakClassifier.classify(
                DATE, List.of(), List.of(payment(REFERENCE, 1200, "EUR", OTHER_DATE))))
        .isEmpty();
    assertThat(
            BreakClassifier.classify(
                DATE, List.of(), List.of(payment(REFERENCE, 1200, "EUR", DATE.plusDays(1)))))
        .isEmpty();
  }

  @Test
  void outputIsSortedByReference() {
    assertThat(
            BreakClassifier.classify(
                DATE,
                List.of(
                    line(2, "ZZZ-0001", 300, "USD", DATE),
                    line(3, "AAA-0001", 100, "EUR", DATE),
                    line(4, "MMM-0001", 200, "GBP", OTHER_DATE)),
                List.of(payment("BBB-0001", 400, "EUR", DATE))))
        .containsExactly(
            new Break(
                BreakType.MISSING_IN_LEDGER, "AAA-0001", 3, null, null, null, 100L, "EUR", DATE),
            new Break(
                BreakType.MISSING_IN_STATEMENT,
                "BBB-0001",
                null,
                400L,
                "EUR",
                DATE,
                null,
                null,
                null),
            new Break(
                BreakType.MISSING_IN_LEDGER,
                "MMM-0001",
                4,
                null,
                null,
                null,
                200L,
                "GBP",
                OTHER_DATE),
            new Break(
                BreakType.MISSING_IN_LEDGER, "ZZZ-0001", 2, null, null, null, 300L, "USD", DATE));
  }

  @Test
  void everyReferenceGetsAtMostOneBreak() {
    List<StatementLine> lines =
        List.of(
            line(2, "TLY-0003", 1300, "USD", OTHER_DATE),
            line(3, "TLY-0002", 1300, "USD", OTHER_DATE),
            line(4, REFERENCE, 1300, "USD", OTHER_DATE),
            line(5, REFERENCE, 1400, "GBP", DATE),
            line(6, "TLY-0004", 1200, "EUR", DATE));
    List<LedgerPayment> payments =
        List.of(
            payment(REFERENCE, 1200, "EUR", DATE),
            payment("TLY-0002", 1200, "EUR", DATE),
            payment("TLY-0004", 1200, "EUR", DATE),
            payment("TLY-0005", 1200, "EUR", DATE),
            payment("TLY-0006", 1200, "EUR", OTHER_DATE));
    List<Break> breaks = BreakClassifier.classify(DATE, lines, payments);
    assertThat(breaks)
        .containsExactly(
            new Break(
                BreakType.DUPLICATE_IN_STATEMENT,
                REFERENCE,
                4,
                1200L,
                "EUR",
                DATE,
                1300L,
                "USD",
                OTHER_DATE),
            new Break(
                BreakType.CURRENCY_MISMATCH,
                "TLY-0002",
                3,
                1200L,
                "EUR",
                DATE,
                1300L,
                "USD",
                OTHER_DATE),
            new Break(
                BreakType.MISSING_IN_LEDGER,
                "TLY-0003",
                2,
                null,
                null,
                null,
                1300L,
                "USD",
                OTHER_DATE),
            new Break(
                BreakType.MISSING_IN_STATEMENT,
                "TLY-0005",
                null,
                1200L,
                "EUR",
                DATE,
                null,
                null,
                null));
    assertThat(breaks).extracting(Break::reference).doesNotHaveDuplicates();
  }

  private static void assertComparison(
      BreakType type,
      long statementAmount,
      String statementCurrency,
      LocalDate statementDate,
      long ledgerAmount,
      String ledgerCurrency,
      LocalDate ledgerDate) {
    assertThat(
            BreakClassifier.classify(
                DATE,
                List.of(line(7, REFERENCE, statementAmount, statementCurrency, statementDate)),
                List.of(payment(REFERENCE, ledgerAmount, ledgerCurrency, ledgerDate))))
        .containsExactly(
            new Break(
                type,
                REFERENCE,
                7,
                ledgerAmount,
                ledgerCurrency,
                ledgerDate,
                statementAmount,
                statementCurrency,
                statementDate));
  }

  private static StatementLine line(
      int number, String reference, long amount, String currency, LocalDate date) {
    return new StatementLine(number, reference, "DEMO-OK", amount, currency, date);
  }

  private static LedgerPayment payment(
      String reference, long amount, String currency, LocalDate date) {
    return new LedgerPayment(reference, amount, currency, date);
  }
}
