package tally.proof.concurrency;

import java.util.random.RandomGenerator;

public enum Workload {
  UNIFORM {
    @Override
    public int source(RandomGenerator rnd, int poolSize) {
      return rnd.nextInt(poolSize);
    }
  },
  HOT {
    @Override
    public int source(RandomGenerator rnd, int poolSize) {
      return rnd.nextDouble() < 0.8 ? rnd.nextInt(10) : rnd.nextInt(poolSize);
    }
  };

  public abstract int source(RandomGenerator rnd, int poolSize);

  public int destination(RandomGenerator rnd, int poolSize, int source) {
    int destination;
    do {
      destination = rnd.nextInt(poolSize);
    } while (destination == source);
    return destination;
  }
}
