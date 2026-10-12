package tally.proof.harness;

public record Sizes(int sequenceLength, int concurrencyOperations, int concurrencyThreads) {

  public static Sizes current() {
    String size = System.getProperty("proof.size", "ci");
    return switch (size) {
      case "ci" -> new Sizes(40, 20_000, 32);
      case "eval" -> new Sizes(100, 1_000_000, 64);
      default -> throw new IllegalArgumentException("unknown proof size: " + size);
    };
  }
}
