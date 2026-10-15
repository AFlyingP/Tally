package tally.ledger.core;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public final class StatementParser {

  private static final String HEADER = "reference,creditor_account,amount,currency,value_date";

  private StatementParser() {}

  public static List<StatementLine> parse(String csv) throws Malformed {
    if (csv.isEmpty()) {
      throw new Malformed(1, "missing header");
    }
    String[] lines = csv.split("\n", -1);
    int end = lines.length;
    if (lines[end - 1].isEmpty()) {
      end--;
    }
    List<StatementLine> result = new ArrayList<>();
    for (int index = 0; index < end; index++) {
      String line = lines[index];
      if (line.endsWith("\r")) {
        line = line.substring(0, line.length() - 1);
      }
      int number = index + 1;
      if (index == 0) {
        if (!HEADER.equals(line)) {
          throw new Malformed(number, "wrong header");
        }
      } else {
        result.add(parseLine(line, number));
      }
    }
    return result;
  }

  private static StatementLine parseLine(String line, int number) throws Malformed {
    if (line.isEmpty()) {
      throw new Malformed(number, "blank line");
    }
    String[] fields = line.split(",", -1);
    if (fields.length != 5) {
      throw new Malformed(number, "wrong field count");
    }
    if (!fields[0].matches("[A-Z0-9-]{8,40}")) {
      throw new Malformed(number, "invalid reference");
    }
    if (!fields[1].matches("[A-Z0-9-]{4,34}")) {
      throw new Malformed(number, "invalid creditor account");
    }
    if (!fields[2].matches("[1-9][0-9]{0,17}")) {
      throw new Malformed(number, "invalid amount");
    }
    if (!fields[3].matches("[A-Z]{3}")) {
      throw new Malformed(number, "invalid currency");
    }
    if (!fields[4].matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
      throw new Malformed(number, "invalid value date");
    }
    LocalDate date;
    try {
      date = LocalDate.parse(fields[4]);
    } catch (DateTimeParseException e) {
      throw new Malformed(number, "invalid value date");
    }
    return new StatementLine(
        number, fields[0], fields[1], Long.parseLong(fields[2]), fields[3], date);
  }

  public static final class Malformed extends Exception {

    private final int line;
    private final String reason;

    public Malformed(int line, String reason) {
      super(reason);
      this.line = line;
      this.reason = reason;
    }

    public int line() {
      return line;
    }

    public String reason() {
      return reason;
    }
  }
}
