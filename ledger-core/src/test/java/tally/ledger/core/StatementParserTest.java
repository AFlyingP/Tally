package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatementParserTest {

  private static final String HEADER = "reference,creditor_account,amount,currency,value_date";
  private static final String VALID = "TLY-0001,DEMO-OK,1200,EUR,2026-03-10";

  @Test
  void parsesValidFile() throws StatementParser.Malformed {
    assertThat(
            StatementParser.parse(
                HEADER + "\n" + VALID + "\nTLY-0002,BANK-02,999999999999999999,USD,2026-03-09\n"))
        .containsExactly(
            new StatementLine(2, "TLY-0001", "DEMO-OK", 1200, "EUR", LocalDate.of(2026, 3, 10)),
            new StatementLine(
                3, "TLY-0002", "BANK-02", 999999999999999999L, "USD", LocalDate.of(2026, 3, 9)));
  }

  @Test
  void acceptsHeaderOnly() throws StatementParser.Malformed {
    for (String ending : List.of("", "\n", "\r\n")) {
      assertThat(StatementParser.parse(HEADER + ending)).isEmpty();
    }
  }

  @Test
  void acceptsCrLfAndMissingFinalNewline() throws StatementParser.Malformed {
    List<StatementLine> expected =
        List.of(
            new StatementLine(2, "TLY-0001", "DEMO-OK", 1200, "EUR", LocalDate.of(2026, 3, 10)));
    for (String csv :
        List.of(
            HEADER + "\r\n" + VALID + "\r\n",
            HEADER + "\r\n" + VALID,
            HEADER + "\n" + VALID,
            HEADER + "\r\n" + VALID + "\r")) {
      assertThat(StatementParser.parse(csv)).containsExactlyElementsOf(expected);
    }
  }

  @Test
  void emptyInputIsMissingHeader() {
    assertMalformed("", 1, "missing header");
  }

  @Test
  void wrongHeader() {
    for (String header :
        List.of(
            "",
            " " + HEADER,
            HEADER + " ",
            "Reference,creditor_account,amount,currency,value_date")) {
      assertMalformed(header + "\n" + VALID, 1, "wrong header");
    }
    assertMalformed(HEADER + "\r\r\n" + VALID, 1, "wrong header");
  }

  @Test
  void blankLine() {
    assertMalformed(HEADER + "\n\n", 2, "blank line");
    assertMalformed(HEADER + "\n" + VALID + "\n\n", 3, "blank line");
    assertMalformed(HEADER + "\r\n" + VALID + "\r\n\r\n", 3, "blank line");
  }

  @Test
  void wrongFieldCount() {
    for (String line : List.of("TLY-0001,DEMO-OK,1200,EUR", VALID + ",extra", VALID + ",")) {
      assertMalformed(HEADER + "\n" + line, 2, "wrong field count");
    }
  }

  @Test
  void invalidReference() {
    for (String reference :
        List.of(
            "tly-0001", "A".repeat(7), "A".repeat(41), "TLY_0001", " TLY-0001", "TLY-0001 ", "")) {
      assertInvalidField(0, reference, "invalid reference");
    }
  }

  @Test
  void invalidCreditorAccount() {
    for (String account :
        List.of("abcD", "ABC", "A".repeat(35), "BANK_01", " DEMO-OK", "DEMO-OK ", "")) {
      assertInvalidField(1, account, "invalid creditor account");
    }
  }

  @Test
  void invalidAmount() {
    for (String amount :
        List.of("0", "01", "1.5", "9999999999999999999", "-1", "+1", "1e2", " 1", "1 ", "")) {
      assertInvalidField(2, amount, "invalid amount");
    }
  }

  @Test
  void invalidCurrency() {
    for (String currency : List.of("eur", "EU", "EURO", "EU1", " EUR", "EUR ", "")) {
      assertInvalidField(3, currency, "invalid currency");
    }
  }

  @Test
  void invalidValueDate() {
    for (String date :
        List.of(
            "2026-02-30",
            "2026-02-29",
            "2026-13-10",
            "2026-03-00",
            "2026-3-10",
            "26-03-10",
            "+2026-03-10",
            "2026-03-10 ",
            "")) {
      assertInvalidField(4, date, "invalid value date");
    }
    assertMalformed(HEADER + "\n" + VALID + "\r\r\n", 2, "invalid value date");
  }

  @Test
  void firstBadFieldWins() {
    assertMalformed(HEADER + "\ninvalid,DEMO-OK,0,EUR,2026-03-10", 2, "invalid reference");
    assertMalformed(HEADER + "\nTLY-0001,bad,0,eur,bad", 2, "invalid creditor account");
    assertMalformed(HEADER + "\nTLY-0001,DEMO-OK,0,eur,bad", 2, "invalid amount");
    assertMalformed(HEADER + "\nTLY-0001,DEMO-OK,1,eur,bad", 2, "invalid currency");
    assertMalformed(
        HEADER + "\n" + VALID + "\ninvalid,DEMO-OK,0,EUR,2026-03-10\n\n", 3, "invalid reference");
  }

  @Test
  void acceptsFieldBoundariesAndLeapDay() throws StatementParser.Malformed {
    String reference = "A0-" + "Z".repeat(37);
    String account = "B1-" + "Y".repeat(31);
    assertThat(
            StatementParser.parse(HEADER + "\n" + reference + "," + account + ",1,ZZZ,2024-02-29"))
        .containsExactly(
            new StatementLine(2, reference, account, 1, "ZZZ", LocalDate.of(2024, 2, 29)));
    assertThat(StatementParser.parse(HEADER + "\nABC0123-,AB0-,10,EUR,2026-01-01"))
        .containsExactly(
            new StatementLine(2, "ABC0123-", "AB0-", 10, "EUR", LocalDate.of(2026, 1, 1)));
  }

  private static void assertInvalidField(int index, String value, String reason) {
    String[] fields = VALID.split(",");
    fields[index] = value;
    assertMalformed(HEADER + "\n" + VALID + "\n" + String.join(",", fields), 3, reason);
  }

  private static void assertMalformed(String csv, int line, String reason) {
    assertThatThrownBy(() -> StatementParser.parse(csv))
        .isInstanceOfSatisfying(
            StatementParser.Malformed.class,
            exception -> {
              assertThat(exception.line()).isEqualTo(line);
              assertThat(exception.reason()).isEqualTo(reason);
              assertThat(exception).hasMessage(reason);
            });
  }
}
