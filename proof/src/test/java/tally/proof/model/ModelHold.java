package tally.proof.model;

import java.time.Instant;

public final class ModelHold {

  private final int account;
  private final long amount;
  private final Instant expiresAt;
  private String state = "ACTIVE";

  ModelHold(int account, long amount, Instant expiresAt) {
    this.account = account;
    this.amount = amount;
    this.expiresAt = expiresAt;
  }

  public int account() {
    return account;
  }

  public long amount() {
    return amount;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public String state() {
    return state;
  }

  void state(String state) {
    this.state = state;
  }
}
