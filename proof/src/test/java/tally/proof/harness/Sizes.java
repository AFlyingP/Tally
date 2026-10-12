package tally.proof.harness;

public record Sizes(int sequenceLength) {

  public static Sizes current() {
    String size = System.getProperty("proof.size", "ci");
    return switch (size) {
      case "ci" -> new Sizes(40);
      case "eval" -> new Sizes(100);
      default -> throw new IllegalArgumentException("unknown proof size: " + size);
    };
  }
}
