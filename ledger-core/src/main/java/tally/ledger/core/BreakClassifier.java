package tally.ledger.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BreakClassifier {

  private BreakClassifier() {}

  public record LedgerPayment(
      String reference, long amount, String currency, LocalDate valueDate) {}

  public record Break(
      BreakType type,
      String reference,
      Integer lineNumber,
      Long ledgerAmount,
      String ledgerCurrency,
      LocalDate ledgerDate,
      Long statementAmount,
      String statementCurrency,
      LocalDate statementDate) {}

  public static List<Break> classify(
      LocalDate businessDate, List<StatementLine> lines, List<LedgerPayment> payments) {
    Map<String, LedgerPayment> ledger = new HashMap<>();
    for (LedgerPayment payment : payments) {
      ledger.put(payment.reference(), payment);
    }
    Map<String, List<StatementLine>> statement = new HashMap<>();
    for (StatementLine line : lines) {
      statement.computeIfAbsent(line.reference(), key -> new ArrayList<>()).add(line);
    }
    List<Break> result = new ArrayList<>();
    for (List<StatementLine> group : statement.values()) {
      StatementLine line = group.getFirst();
      LedgerPayment payment = ledger.get(line.reference());
      BreakType type;
      if (group.size() >= 2) {
        type = BreakType.DUPLICATE_IN_STATEMENT;
      } else if (payment == null) {
        type = BreakType.MISSING_IN_LEDGER;
      } else if (!line.currency().equals(payment.currency())) {
        type = BreakType.CURRENCY_MISMATCH;
      } else if (line.amount() != payment.amount()) {
        type = BreakType.AMOUNT_MISMATCH;
      } else if (!line.valueDate().equals(payment.valueDate())
          || !line.valueDate().equals(businessDate)) {
        type = BreakType.DATE_MISMATCH;
      } else {
        continue;
      }
      result.add(
          new Break(
              type,
              line.reference(),
              line.lineNumber(),
              payment == null ? null : payment.amount(),
              payment == null ? null : payment.currency(),
              payment == null ? null : payment.valueDate(),
              line.amount(),
              line.currency(),
              line.valueDate()));
    }
    for (LedgerPayment payment : payments) {
      if (payment.valueDate().equals(businessDate) && !statement.containsKey(payment.reference())) {
        result.add(
            new Break(
                BreakType.MISSING_IN_STATEMENT,
                payment.reference(),
                null,
                payment.amount(),
                payment.currency(),
                payment.valueDate(),
                null,
                null,
                null));
      }
    }
    result.sort(Comparator.comparing(Break::reference));
    return result;
  }
}
