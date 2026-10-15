package tally.proof.model;

public final class ModelPayment {

  private final int source;
  private final long amount;
  private final String behaviour;
  private String state = "INITIATED";

  ModelPayment(int source, long amount, String behaviour) {
    this.source = source;
    this.amount = amount;
    this.behaviour = behaviour;
  }

  public int source() {
    return source;
  }

  public long amount() {
    return amount;
  }

  public String behaviour() {
    return behaviour;
  }

  public String state() {
    return state;
  }

  void state(String state) {
    this.state = state;
  }
}
