package tally.proof.seeded;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/** Builds seeded transaction events and the alerts they should produce. */
public final class TrafficGenerator {

  private static final Instant BASE_TIME = Instant.parse("2026-03-10T00:00:00Z");
  private static final long PATTERN_SPAN_SECONDS = 48 * 60 * 60;
  private static final long ID_SEED_XOR = 0x74b79d10c3e2568aL;

  private TrafficGenerator() {}

  public record Traffic(
      List<Event> events, Set<AlertKey> expected, Map<String, Integer> expectedPerRule) {}

  public record Event(UUID eventId, String key, String json) {}

  public record AlertKey(String rule, UUID accountId, String triggerKey) {}

  public static Traffic generate(int perRule, long seed) {
    if (perRule < 0) {
      throw new IllegalArgumentException("per-rule count must not be negative");
    }
    List<Pattern> patterns = new ArrayList<>();
    for (String rule : List.of("R1", "R2", "R3")) {
      for (int index = 0; index < perRule; index++) {
        patterns.add(new Pattern(rule, true, index));
        patterns.add(new Pattern(rule, false, index));
      }
    }

    SplittableRandom timeRandom = new SplittableRandom(seed);
    SplittableRandom valueRandom = new SplittableRandom(seed ^ ID_SEED_XOR);
    List<Event> events = new ArrayList<>();
    Set<AlertKey> expected = new LinkedHashSet<>();
    for (int index = 0; index < patterns.size(); index++) {
      Pattern pattern = patterns.get(index);
      Instant start = patternStart(index, patterns.size(), timeRandom);
      UUID source = uuid(valueRandom);
      UUID sink = uuid(valueRandom);
      UUID settlement = uuid(valueRandom);
      switch (pattern.rule()) {
        case "R1" -> addVelocity(pattern, start, source, sink, valueRandom, events, expected);
        case "R2" -> addStructuring(pattern, start, source, sink, valueRandom, events, expected);
        case "R3" ->
            addPassThrough(pattern, start, source, sink, settlement, valueRandom, events, expected);
        default -> throw new IllegalStateException("unknown rule: " + pattern.rule());
      }
    }
    shuffle(events, new SplittableRandom(seed));

    Map<String, Integer> expectedPerRule = new LinkedHashMap<>();
    for (String rule : List.of("R1", "R2", "R3")) {
      expectedPerRule.put(rule, perRule);
    }
    return new Traffic(
        List.copyOf(events),
        Collections.unmodifiableSet(expected),
        Collections.unmodifiableMap(expectedPerRule));
  }

  private static void addVelocity(
      Pattern pattern,
      Instant start,
      UUID source,
      UUID sink,
      SplittableRandom random,
      List<Event> events,
      Set<AlertKey> expected) {
    UUID trigger = null;
    for (int index = 0; index < 11; index++) {
      long seconds = pattern.violation() || index < 10 ? 54L * index : 601;
      BuiltEvent built = transfer(random, start.plusSeconds(seconds), 100, source, sink);
      events.add(built.event());
      if (index == 10) {
        trigger = built.transactionId();
      }
    }
    if (pattern.violation()) {
      expected.add(new AlertKey("R1", source, trigger.toString()));
    }
  }

  private static void addStructuring(
      Pattern pattern,
      Instant start,
      UUID source,
      UUID sink,
      SplittableRandom random,
      List<Event> events,
      Set<AlertKey> expected) {
    UUID trigger = null;
    int thirdAmount = 950_000;
    long thirdSeconds = 22L * 60 * 60;
    if (!pattern.violation()) {
      switch (pattern.index() % 3) {
        case 0 -> thirdAmount = 1_000_000;
        case 1 -> thirdAmount = 899_999;
        case 2 -> thirdSeconds = 86_401;
        default -> throw new IllegalStateException("unknown near-miss pattern");
      }
    }
    for (int index = 0; index < 3; index++) {
      long seconds = index == 2 ? thirdSeconds : 11L * 60 * 60 * index;
      int amount = index == 2 ? thirdAmount : 950_000;
      BuiltEvent built = transfer(random, start.plusSeconds(seconds), amount, source, sink);
      events.add(built.event());
      if (index == 2) {
        trigger = built.transactionId();
      }
    }
    if (pattern.violation()) {
      expected.add(new AlertKey("R2", source, trigger.toString()));
    }
  }

  private static void addPassThrough(
      Pattern pattern,
      Instant start,
      UUID source,
      UUID sink,
      UUID settlement,
      SplittableRandom random,
      List<Event> events,
      Set<AlertKey> expected) {
    BuiltEvent deposit = deposit(random, start, source, settlement);
    events.add(deposit.event());
    int amount = 90_000;
    long seconds = 29L * 60;
    if (!pattern.violation()) {
      if (pattern.index() % 2 == 0) {
        amount = 89_999;
      } else {
        seconds = 1_801;
      }
    }
    BuiltEvent transfer = transfer(random, start.plusSeconds(seconds), amount, source, sink);
    events.add(transfer.event());
    if (pattern.violation()) {
      expected.add(new AlertKey("R3", source, deposit.transactionId().toString()));
    }
  }

  private static BuiltEvent transfer(
      SplittableRandom random, Instant postedAt, int amount, UUID source, UUID sink) {
    return event(
        random,
        "TRANSFER",
        postedAt,
        amount,
        List.of(
            new Entry(source, "CUSTOMER", "CREDIT", "DEBIT", amount),
            new Entry(sink, "CUSTOMER", "CREDIT", "CREDIT", amount)));
  }

  private static BuiltEvent deposit(
      SplittableRandom random, Instant postedAt, UUID customer, UUID settlement) {
    return event(
        random,
        "DEPOSIT",
        postedAt,
        100_000,
        List.of(
            new Entry(customer, "CUSTOMER", "CREDIT", "CREDIT", 100_000),
            new Entry(settlement, "SETTLEMENT", "DEBIT", "DEBIT", 100_000)));
  }

  private static BuiltEvent event(
      SplittableRandom random, String kind, Instant postedAt, int amount, List<Entry> entries) {
    UUID eventId = uuid(random);
    UUID transactionId = uuid(random);
    UUID requestId = uuid(random);
    StringBuilder json = new StringBuilder();
    json.append("{\"event_id\":\"")
        .append(eventId)
        .append("\",\"schema_version\":1,\"type\":\"transaction.posted\",\"transaction_id\":\"")
        .append(transactionId)
        .append("\",\"kind\":\"")
        .append(kind)
        .append("\",\"currency\":\"USD\",\"amount\":")
        .append(amount)
        .append(",\"posted_at\":\"")
        .append(postedAt)
        .append("\",\"key_account_id\":\"")
        .append(entries.getFirst().accountId())
        .append("\",\"request_id\":\"")
        .append(requestId)
        .append("\",\"reverses_transaction_id\":null,\"entries\":[");
    for (int index = 0; index < entries.size(); index++) {
      if (index > 0) {
        json.append(',');
      }
      Entry entry = entries.get(index);
      json.append("{\"account_id\":\"")
          .append(entry.accountId())
          .append("\",\"account_kind\":\"")
          .append(entry.accountKind())
          .append("\",\"normal_side\":\"")
          .append(entry.normalSide())
          .append("\",\"side\":\"")
          .append(entry.side())
          .append("\",\"amount\":")
          .append(entry.amount())
          .append('}');
    }
    json.append("]}");
    return new BuiltEvent(
        new Event(eventId, entries.getFirst().accountId().toString(), json.toString()),
        transactionId);
  }

  private static Instant patternStart(int index, int count, SplittableRandom random) {
    long slotStart = (long) index * PATTERN_SPAN_SECONDS / count;
    long slotEnd = (long) (index + 1) * PATTERN_SPAN_SECONDS / count;
    long slotWidth = slotEnd - slotStart;
    long offset = slotWidth == 0 ? 0 : random.nextLong(slotWidth);
    return BASE_TIME.plusSeconds(slotStart + offset);
  }

  private static UUID uuid(SplittableRandom random) {
    long mostSignificantBits = (random.nextLong() & 0xffffffffffff0fffL) | 0x0000000000004000L;
    long leastSignificantBits = (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
    return new UUID(mostSignificantBits, leastSignificantBits);
  }

  private static void shuffle(List<Event> events, SplittableRandom random) {
    for (int index = events.size() - 1; index > 0; index--) {
      int other = random.nextInt(index + 1);
      Collections.swap(events, index, other);
    }
  }

  private record Pattern(String rule, boolean violation, int index) {}

  private record Entry(
      UUID accountId, String accountKind, String normalSide, String side, int amount) {}

  private record BuiltEvent(Event event, UUID transactionId) {}
}
