package tally.proof.harness;

public final class Seeds {

  public static final String PROPERTY_SEED = "20260101";

  private Seeds() {}

  public static long seed() {
    return Long.parseLong(System.getProperty("proof.seed", PROPERTY_SEED));
  }

  public static void print(String suite) {
    System.out.println("seed=" + seed() + " suite=" + suite);
  }
}
