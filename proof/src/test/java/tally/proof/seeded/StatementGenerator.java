package tally.proof.seeded;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

public final class StatementGenerator {

  private static final long MAX_AMOUNT = 999_999_999_999_999_999L;
  private static final List<String> TYPES =
      List.of(
          "MISSING_IN_STATEMENT",
          "AMOUNT_MISMATCH",
          "CURRENCY_MISMATCH",
          "DATE_MISMATCH",
          "DUPLICATE_IN_STATEMENT");

  private StatementGenerator() {}

  public record Seeded(String csv, Map<String, String> expected) {}

  public static Seeded alter(String cleanCsv, int perType, long seed) {
    List<String> lines = new ArrayList<>(cleanCsv.lines().toList());
    if (perType < 0 || lines.size() - 1 < 5L * perType) {
      throw new IllegalArgumentException("not enough statement lines");
    }
    if (perType == 0) {
      return new Seeded(cleanCsv, Map.of());
    }
    String date = lines.get(1).split(",", -1)[4];
    List<Integer> choices = new ArrayList<>();
    for (int line = 1; line < lines.size(); line++) {
      choices.add(line);
    }
    SplittableRandom random = new SplittableRandom(seed);
    Map<String, String> expected = new LinkedHashMap<>();
    Set<Integer> removed = new HashSet<>();
    List<String> duplicates = new ArrayList<>();
    for (int pick = 0; pick < 5 * perType; pick++) {
      int selected = random.nextInt(pick, choices.size());
      int line = choices.get(selected);
      choices.set(selected, choices.get(pick));
      choices.set(pick, line);
      String[] fields = lines.get(line).split(",", -1);
      int alteration = pick / perType;
      expected.put(fields[0], TYPES.get(alteration));
      switch (alteration) {
        case 0 -> removed.add(line);
        case 1 -> {
          long amount = Long.parseLong(fields[2]);
          int delta = random.nextInt(1, 101);
          fields[2] = Long.toString(amount <= MAX_AMOUNT - delta ? amount + delta : amount - delta);
          lines.set(line, String.join(",", fields));
        }
        case 2 -> {
          fields[3] = "USD";
          lines.set(line, String.join(",", fields));
        }
        case 3 -> {
          fields[4] = "2026-03-09";
          lines.set(line, String.join(",", fields));
        }
        case 4 -> duplicates.add(lines.get(line));
        default -> throw new IllegalStateException("unknown alteration");
      }
    }
    List<String> altered = new ArrayList<>();
    for (int line = 0; line < lines.size(); line++) {
      if (!removed.contains(line)) {
        altered.add(lines.get(line));
      }
    }
    altered.addAll(duplicates);
    for (int counter = 1; counter <= perType; counter++) {
      String reference = String.format(Locale.ROOT, "SEED-%08d", counter);
      altered.add(reference + ",SEED-NEW,100,EUR," + date);
      expected.put(reference, "MISSING_IN_LEDGER");
    }
    return new Seeded(String.join("\n", altered) + "\n", Map.copyOf(expected));
  }

  public static String malform(String cleanCsv, int lineNumber) {
    String[] lines = cleanCsv.split("\n", -1);
    String[] fields = lines[lineNumber - 1].split(",", -1);
    fields[2] = "abc";
    lines[lineNumber - 1] = String.join(",", fields);
    return String.join("\n", lines);
  }
}
