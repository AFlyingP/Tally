package tally.proof.fault;

public enum Scenario {
  API_KILL(1),
  CONNECTION_CUT(2),
  SLOW_DATABASE(3),
  DATABASE_RESTART(4),
  SAME_KEY_BURST(5),
  SCHEDULER_KILL(6),
  RELAY_KILL(7),
  BROKER_RESTART(8),
  EXTBANK_FAULTS(9),
  ORCHESTRATOR_KILL(10),
  MONITOR_KILL(11);

  private final int id;

  Scenario(int id) {
    this.id = id;
  }

  public int id() {
    return id;
  }
}
