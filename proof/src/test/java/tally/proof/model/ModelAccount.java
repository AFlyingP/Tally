package tally.proof.model;

import java.util.UUID;

final class ModelAccount {

  private final UUID id;
  private final long minBalance;
  private final boolean creditNormal;
  private String status = "OPEN";
  private long balance;
  private long held;
  private int entryCount;

  ModelAccount(UUID id, long minBalance, boolean creditNormal) {
    this.id = id;
    this.minBalance = minBalance;
    this.creditNormal = creditNormal;
  }

  UUID id() {
    return id;
  }

  long minBalance() {
    return minBalance;
  }

  boolean creditNormal() {
    return creditNormal;
  }

  String status() {
    return status;
  }

  void status(String status) {
    this.status = status;
  }

  long balance() {
    return balance;
  }

  long held() {
    return held;
  }

  int entryCount() {
    return entryCount;
  }

  void post(long balance) {
    entryCount = Math.addExact(entryCount, 1);
    this.balance = balance;
  }
}
