package tally.proof.harness;

import java.util.List;

public record Sizes(
    int sequenceLength,
    int concurrencyOperations,
    int concurrencyThreads,
    int faultSeconds,
    int faultThreads,
    int faultRuns,
    int loadSeconds,
    List<Integer> loadClients,
    List<String> loadWorkloads,
    int loadRuns) {

  public static Sizes current() {
    String size = System.getProperty("proof.size", "ci");
    return switch (size) {
      case "ci" -> new Sizes(40, 20_000, 32, 6, 8, 1, 5, List.of(8), List.of("hot"), 1);
      case "eval" ->
          new Sizes(
              100,
              1_000_000,
              64,
              15,
              16,
              20,
              30,
              List.of(8, 32, 128),
              List.of("uniform", "hot"),
              5);
      default -> throw new IllegalArgumentException("unknown proof size: " + size);
    };
  }
}
