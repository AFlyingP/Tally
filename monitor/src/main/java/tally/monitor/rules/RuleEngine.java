package tally.monitor.rules;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tally.monitor.ingest.Movement;

@Component
public class RuleEngine {

  private static final BigInteger HUNDRED = BigInteger.valueOf(100);
  private static final Comparator<Movement> MOVEMENT_ORDER =
      Comparator.comparing(Movement::eventTime).thenComparing(Movement::transactionId);

  private final RuleProperties properties;

  public RuleEngine(RuleProperties properties) {
    this.properties = properties;
  }

  public List<AlertCandidate> evaluate(UUID accountId, List<Movement> movements, Movement arrived) {
    if (!accountId.equals(arrived.accountId())) {
      return List.of();
    }

    List<Movement> accountMovements =
        movements.stream()
            .filter(movement -> accountId.equals(movement.accountId()))
            .sorted(MOVEMENT_ORDER)
            .toList();
    Map<String, AlertCandidate> candidates = new LinkedHashMap<>();

    if (arrived.counted() && "OUT".equals(arrived.direction())) {
      addVelocityCandidates(accountId, accountMovements, arrived, candidates);
    }
    if (arrived.r2Candidate()) {
      addStructuringCandidates(accountId, accountMovements, arrived, candidates);
    }
    if (arrived.counted() && "OUT".equals(arrived.direction())) {
      addPassThroughCandidates(accountId, accountMovements, arrived, candidates);
    } else if (arrived.counted() && "IN".equals(arrived.direction())) {
      addPassThroughCandidate(accountId, accountMovements, arrived, candidates);
    }

    return List.copyOf(candidates.values());
  }

  public static boolean isR2Candidate(long amount, RuleProperties.R2 r2) {
    long threshold = r2.threshold();
    if (amount >= threshold) {
      return false;
    }

    long amountProductHigh = Math.multiplyHigh(amount, 100L);
    long thresholdProductHigh = Math.multiplyHigh(threshold, (long) r2.lowerPercent());
    if (amountProductHigh != 0 || thresholdProductHigh != 0) {
      return BigInteger.valueOf(amount)
              .multiply(HUNDRED)
              .compareTo(
                  BigInteger.valueOf(threshold).multiply(BigInteger.valueOf(r2.lowerPercent())))
          >= 0;
    }
    return amount * 100 >= threshold * r2.lowerPercent();
  }

  private void addVelocityCandidates(
      UUID accountId,
      List<Movement> movements,
      Movement arrived,
      Map<String, AlertCandidate> candidates) {
    long windowSeconds = properties.r1().windowSeconds();
    Instant reevaluateBefore = arrived.eventTime().plusSeconds(windowSeconds);
    for (Movement trigger : movements) {
      if (!isCountedOut(trigger)
          || trigger.eventTime().isBefore(arrived.eventTime())
          || !trigger.eventTime().isBefore(reevaluateBefore)) {
        continue;
      }
      Instant windowStart = trigger.eventTime().minusSeconds(windowSeconds);
      long count =
          movements.stream()
              .filter(RuleEngine::isCountedOut)
              .filter(movement -> movement.eventTime().isAfter(windowStart))
              .filter(movement -> !movement.eventTime().isAfter(trigger.eventTime()))
              .count();
      if (count > properties.r1().maxCount()) {
        addCandidate(
            candidates,
            new AlertCandidate(
                Rule.R1,
                accountId,
                trigger.transactionId().toString(),
                trigger.eventTime(),
                "count=" + count + " window_seconds=" + windowSeconds));
      }
    }
  }

  private void addStructuringCandidates(
      UUID accountId,
      List<Movement> movements,
      Movement arrived,
      Map<String, AlertCandidate> candidates) {
    long windowSeconds = properties.r2().windowSeconds();
    Instant reevaluateBefore = arrived.eventTime().plusSeconds(windowSeconds);
    for (Movement trigger : movements) {
      if (!trigger.r2Candidate()
          || trigger.eventTime().isBefore(arrived.eventTime())
          || !trigger.eventTime().isBefore(reevaluateBefore)) {
        continue;
      }
      Instant windowStart = trigger.eventTime().minusSeconds(windowSeconds);
      long count =
          movements.stream()
              .filter(Movement::r2Candidate)
              .filter(movement -> movement.eventTime().isAfter(windowStart))
              .filter(movement -> !movement.eventTime().isAfter(trigger.eventTime()))
              .count();
      if (count >= properties.r2().minCount()) {
        addCandidate(
            candidates,
            new AlertCandidate(
                Rule.R2,
                accountId,
                trigger.transactionId().toString(),
                trigger.eventTime(),
                "count=" + count + " window_seconds=" + windowSeconds));
      }
    }
  }

  private void addPassThroughCandidates(
      UUID accountId,
      List<Movement> movements,
      Movement arrived,
      Map<String, AlertCandidate> candidates) {
    Instant reevaluateFrom = arrived.eventTime().minusSeconds(properties.r3().windowSeconds());
    for (Movement trigger : movements) {
      if (isCountedIn(trigger)
          && !trigger.eventTime().isBefore(reevaluateFrom)
          && trigger.eventTime().isBefore(arrived.eventTime())) {
        addPassThroughCandidate(accountId, movements, trigger, candidates);
      }
    }
  }

  private void addPassThroughCandidate(
      UUID accountId,
      List<Movement> movements,
      Movement trigger,
      Map<String, AlertCandidate> candidates) {
    BigInteger sent =
        movements.stream()
            .filter(RuleEngine::isCountedOut)
            .filter(movement -> movement.eventTime().isAfter(trigger.eventTime()))
            .filter(
                movement ->
                    !movement
                        .eventTime()
                        .isAfter(trigger.eventTime().plusSeconds(properties.r3().windowSeconds())))
            .map(movement -> BigInteger.valueOf(movement.amount()))
            .reduce(BigInteger.ZERO, BigInteger::add);
    BigInteger sentProduct = sent.multiply(HUNDRED);
    BigInteger receivedProduct =
        BigInteger.valueOf(trigger.amount())
            .multiply(BigInteger.valueOf(properties.r3().percent()));
    if (sentProduct.compareTo(receivedProduct) >= 0) {
      long windowSeconds = properties.r3().windowSeconds();
      addCandidate(
          candidates,
          new AlertCandidate(
              Rule.R3,
              accountId,
              trigger.transactionId().toString(),
              trigger.eventTime(),
              "received="
                  + trigger.amount()
                  + " sent="
                  + sent
                  + " window_seconds="
                  + windowSeconds));
    }
  }

  private static boolean isCountedOut(Movement movement) {
    return movement.counted() && "OUT".equals(movement.direction());
  }

  private static boolean isCountedIn(Movement movement) {
    return movement.counted() && "IN".equals(movement.direction());
  }

  private static void addCandidate(
      Map<String, AlertCandidate> candidates, AlertCandidate candidate) {
    String key = candidate.rule() + ":" + candidate.accountId() + ":" + candidate.triggerKey();
    candidates.putIfAbsent(key, candidate);
  }

  public record AlertCandidate(
      Rule rule, UUID accountId, String triggerKey, Instant alertTime, String detail) {}
}
