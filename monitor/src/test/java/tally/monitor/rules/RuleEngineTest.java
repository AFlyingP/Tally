package tally.monitor.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tally.monitor.ingest.Movement;
import tally.monitor.rules.RuleEngine.AlertCandidate;

class RuleEngineTest {

  private static final Instant BASE = Instant.parse("2026-03-10T00:00:00Z");
  private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final AtomicLong TRANSACTION_IDS = new AtomicLong();
  private static final RuleProperties PROPERTIES =
      new RuleProperties(
          new RuleProperties.Kafka("kafka:9092", "tally.transactions.v1", "tally-monitor"),
          new RuleProperties.R1(10, 600),
          new RuleProperties.R2(3, 86400, 1000000, 90),
          new RuleProperties.R3(90, 1800),
          86400);

  private final RuleEngine engine = new RuleEngine(PROPERTIES);

  @Test
  void tenOutgoingInWindowDoNotAlert() {
    List<Movement> movements = outgoingAt(10, 1);

    assertThat(engine.evaluate(ACCOUNT_ID, movements, movements.getLast())).isEmpty();
  }

  @Test
  void eleventhOutgoingAlertsWithItsTransactionAsTrigger() {
    List<Movement> movements = outgoingAt(11, 1);
    Movement trigger = movements.getLast();

    assertThat(engine.evaluate(ACCOUNT_ID, movements, trigger))
        .containsExactly(candidate(Rule.R1, trigger, "count=11 window_seconds=600"));
  }

  @Test
  void twelfthAlsoAlerts() {
    List<Movement> movements = outgoingAt(12, 1);
    Movement trigger = movements.getLast();

    assertThat(engine.evaluate(ACCOUNT_ID, movements, trigger))
        .containsExactly(candidate(Rule.R1, trigger, "count=12 window_seconds=600"));
  }

  @Test
  void movementExactlyWindowOldIsOutside() {
    List<Movement> movements = new ArrayList<>();
    movements.add(outgoingAt(0));
    for (int second = 60; second <= 600; second += 60) {
      movements.add(outgoingAt(second));
    }

    assertThat(engine.evaluate(ACCOUNT_ID, movements, movements.getLast())).isEmpty();
  }

  @Test
  void incomingMovementsAreNotCounted() {
    List<Movement> movements = outgoingAt(10, 1);
    Movement incoming = incomingAt(100000, 10);
    List<Movement> withIncoming = new ArrayList<>(movements);
    withIncoming.add(incoming);

    assertThat(engine.evaluate(ACCOUNT_ID, withIncoming, incoming)).isEmpty();
  }

  @Test
  void lateArrivalOfAnEarlyMovementTriggersLaterOnes() {
    List<Movement> movements = outgoingAt(11, 1);
    Movement arrived = movements.getFirst();
    Movement latest = movements.getLast();

    assertThat(engine.evaluate(ACCOUNT_ID, movements, arrived))
        .containsExactly(candidate(Rule.R1, latest, "count=11 window_seconds=600"));
  }

  @Test
  void threeCandidatesWithinDayAlertOnThird() {
    List<Movement> movements = r2CandidatesAt(0, 1, 2);
    Movement trigger = movements.getLast();

    assertThat(engine.evaluate(ACCOUNT_ID, movements, trigger))
        .containsExactly(candidate(Rule.R2, trigger, "count=3 window_seconds=86400"));
  }

  @Test
  void amountExactlyAtThresholdIsNotACandidate() {
    Movement movement = r2Candidate(1000000, 0);

    assertThat(RuleEngine.isR2Candidate(movement.amount(), PROPERTIES.r2())).isFalse();
    assertThat(engine.evaluate(ACCOUNT_ID, List.of(movement), movement)).isEmpty();
  }

  @Test
  void amountExactlyAtLowerBoundIsACandidate() {
    assertThat(RuleEngine.isR2Candidate(900000, PROPERTIES.r2())).isTrue();
  }

  @Test
  void amountJustBelowLowerBoundIsNot() {
    assertThat(RuleEngine.isR2Candidate(899999, PROPERTIES.r2())).isFalse();
  }

  @Test
  void thirdCandidateExactlyOneDayAfterFirstDoesNotAlert() {
    List<Movement> movements = r2CandidatesAt(0, 43200, 86400);

    assertThat(engine.evaluate(ACCOUNT_ID, movements, movements.getLast())).isEmpty();
  }

  @Test
  void fourthCandidateAlertsToo() {
    List<Movement> movements = r2CandidatesAt(0, 1, 2, 3);
    Movement trigger = movements.getLast();

    assertThat(engine.evaluate(ACCOUNT_ID, movements, trigger))
        .containsExactly(candidate(Rule.R2, trigger, "count=4 window_seconds=86400"));
  }

  @Test
  void sendingNinetyPercentWithinWindowAlertsOnTheIncoming() {
    Movement incoming = incomingAt(100000, 0);
    Movement outgoing = outgoingAmountAt(90000, 1);

    assertThat(engine.evaluate(ACCOUNT_ID, List.of(incoming, outgoing), outgoing))
        .containsExactly(
            candidate(Rule.R3, incoming, "received=100000 sent=90000 window_seconds=1800"));
  }

  @Test
  void sendingJustUnderNinetyPercentDoesNot() {
    Movement incoming = incomingAt(100000, 0);
    Movement outgoing = outgoingAmountAt(89999, 1);

    assertThat(engine.evaluate(ACCOUNT_ID, List.of(incoming, outgoing), outgoing)).isEmpty();
  }

  @Test
  void outgoingSumAccumulatesAcrossSeveralTransfers() {
    Movement incoming = incomingAt(100000, 0);
    Movement first = outgoingAmountAt(50000, 1);
    Movement second = outgoingAmountAt(40000, 2);

    assertThat(engine.evaluate(ACCOUNT_ID, List.of(incoming, first, second), second))
        .containsExactly(
            candidate(Rule.R3, incoming, "received=100000 sent=90000 window_seconds=1800"));
  }

  @Test
  void outgoingAfterWindowIsIgnored() {
    Movement incoming = incomingAt(100000, 0);
    Movement outgoing = outgoingAmountAt(100000, 1801);

    assertThat(engine.evaluate(ACCOUNT_ID, List.of(incoming, outgoing), incoming)).isEmpty();
  }

  @Test
  void outgoingAtSameInstantAsIncomingIsIgnored() {
    Movement incoming = incomingAt(100000, 0);
    Movement outgoing = outgoingAmountAt(100000, 0);

    assertThat(engine.evaluate(ACCOUNT_ID, List.of(incoming, outgoing), incoming)).isEmpty();
  }

  @Test
  void incomingArrivingAfterItsOutgoingStillAlerts() {
    Movement incoming = incomingAt(100000, 0);
    Movement outgoing = outgoingAmountAt(90000, 1);

    assertThat(engine.evaluate(ACCOUNT_ID, List.of(outgoing, incoming), incoming))
        .containsExactly(
            candidate(Rule.R3, incoming, "received=100000 sent=90000 window_seconds=1800"));
  }

  @Test
  void resultDoesNotDependOnArrivalOrder() {
    List<Movement> movements = fixedViolatingSet();
    Set<AlertCandidate> expected = evaluateInArrivalOrder(movements);
    Random random = new Random(20260310L);

    assertThat(expected.stream().map(AlertCandidate::rule).toList())
        .contains(Rule.R1, Rule.R2, Rule.R3);
    for (int attempt = 0; attempt < 20; attempt++) {
      List<Movement> shuffled = new ArrayList<>(movements);
      Collections.shuffle(shuffled, random);

      assertThat(evaluateInArrivalOrder(shuffled)).containsExactlyInAnyOrderElementsOf(expected);
    }
  }

  @Test
  void feedingAMovementTwiceAddsNothing() {
    List<Movement> movements = outgoingAt(11, 1);
    List<Movement> repeated = new ArrayList<>(movements);
    repeated.add(movements.getLast());

    assertThat(evaluateInArrivalOrder(repeated))
        .containsExactlyInAnyOrderElementsOf(evaluateInArrivalOrder(movements));
  }

  @Test
  void isR2CandidateHandlesLargeAmountsWithoutOverflow() {
    RuleProperties.R2 largeThreshold = new RuleProperties.R2(3, 86400, 4000000000000000000L, 90);

    assertThat(RuleEngine.isR2Candidate(3600000000000000000L, largeThreshold)).isTrue();
    assertThat(RuleEngine.isR2Candidate(Long.MAX_VALUE, largeThreshold)).isFalse();
  }

  @Test
  void invalidConfigurationReportsKey() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RuleProperties.R1(0, 600))
        .withMessage(
            "invalid configuration: tally.monitor.r1.max-count: must be between 1 and 10000");
  }

  private static List<Movement> outgoingAt(int count, int stepSeconds) {
    List<Movement> movements = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      movements.add(outgoingAt((long) index * stepSeconds));
    }
    return movements;
  }

  private static List<Movement> r2CandidatesAt(long... offsets) {
    List<Movement> movements = new ArrayList<>();
    for (long offset : offsets) {
      movements.add(r2Candidate(950000, offset));
    }
    return movements;
  }

  private static List<Movement> fixedViolatingSet() {
    List<Movement> movements = new ArrayList<>(outgoingAt(11, 1));
    movements.addAll(r2CandidatesAt(100000, 100010, 100020));
    movements.add(incomingAt(100000, 200000));
    movements.add(outgoingAmountAt(90000, 200001));
    for (int index = 0; index < 14; index++) {
      movements.add(movement("OUT", "TRANSFER", 100, false, false, 300000L + index));
    }
    return List.copyOf(movements);
  }

  private static Set<AlertCandidate> evaluateInArrivalOrder(List<Movement> arrivals) {
    Map<MovementKey, Movement> fed = new HashMap<>();
    Set<AlertCandidate> candidates = new HashSet<>();
    for (Movement arrived : arrivals) {
      MovementKey key = new MovementKey(arrived.transactionId(), arrived.accountId());
      if (fed.putIfAbsent(key, arrived) != null) {
        continue;
      }
      List<Movement> loaded =
          fed.values().stream()
              .filter(movement -> ACCOUNT_ID.equals(movement.accountId()))
              .toList();
      candidates.addAll(new RuleEngine(PROPERTIES).evaluate(ACCOUNT_ID, loaded, arrived));
    }
    return Set.copyOf(candidates);
  }

  private static Movement outgoingAt(long offsetSeconds) {
    return movement("OUT", "TRANSFER", 100000, true, false, offsetSeconds);
  }

  private static Movement outgoingAmountAt(long amount, long offsetSeconds) {
    return movement("OUT", "TRANSFER", amount, true, false, offsetSeconds);
  }

  private static Movement incomingAt(long amount, long offsetSeconds) {
    return movement("IN", "TRANSFER", amount, true, false, offsetSeconds);
  }

  private static Movement r2Candidate(long amount, long offsetSeconds) {
    return movement("OUT", "TRANSFER", amount, true, true, offsetSeconds);
  }

  private static Movement movement(
      String direction,
      String kind,
      long amount,
      boolean counted,
      boolean r2Candidate,
      long offsetSeconds) {
    UUID transactionId = new UUID(0, TRANSACTION_IDS.incrementAndGet());
    return new Movement(
        transactionId,
        ACCOUNT_ID,
        direction,
        kind,
        amount,
        "USD",
        counted,
        r2Candidate,
        BASE.plusSeconds(offsetSeconds));
  }

  private static AlertCandidate candidate(Rule rule, Movement trigger, String detail) {
    return new AlertCandidate(
        rule, ACCOUNT_ID, trigger.transactionId().toString(), trigger.eventTime(), detail);
  }

  private record MovementKey(UUID transactionId, UUID accountId) {}
}
