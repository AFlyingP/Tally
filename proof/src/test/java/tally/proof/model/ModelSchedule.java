package tally.proof.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class ModelSchedule {

  private final int source;
  private final int destination;
  private final long amount;
  private final boolean daily;
  private final Instant startAt;
  private String status = "ACTIVE";
  private final List<ModelOccurrence> occurrences = new ArrayList<>();

  public record ModelOccurrence(
      LocalDate date,
      Instant dueAt,
      String state,
      int attempts,
      Instant nextAttemptAt,
      String reason) {}

  ModelSchedule(int source, int destination, long amount, boolean daily, Instant startAt) {
    this.source = source;
    this.destination = destination;
    this.amount = amount;
    this.daily = daily;
    this.startAt = startAt;
  }

  public int source() {
    return source;
  }

  public int destination() {
    return destination;
  }

  public long amount() {
    return amount;
  }

  public boolean daily() {
    return daily;
  }

  public Instant startAt() {
    return startAt;
  }

  public String status() {
    return status;
  }

  void status(String status) {
    this.status = status;
  }

  public List<ModelOccurrence> occurrences() {
    return List.copyOf(occurrences);
  }

  void addOccurrence(ModelOccurrence occurrence) {
    occurrences.add(occurrence);
  }

  void occurrence(int index, ModelOccurrence occurrence) {
    occurrences.set(index, occurrence);
  }
}
