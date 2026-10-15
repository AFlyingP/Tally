package tally.proof.harness;

import java.util.List;

public record Sizes(
    int sequenceLength,
    int concurrencyOperations,
    int concurrencyThreads,
    int faultSeconds,
    int faultThreads,
    int faultRuns,
    int schedulerOccurrences,
    int faultPayments,
    int killPayments,
    int loadSeconds,
    List<Integer> loadClients,
    List<String> loadWorkloads,
    int loadRuns,
    int reconBreaksPerType,
    int reconCleanFiles) {

  public static Sizes current() {
    String size = System.getProperty("proof.size", "ci");
    return switch (size) {
      case "ci" ->
          new Sizes(40, 20_000, 32, 6, 8, 1, 50, 30, 10, 5, List.of(8), List.of("hot"), 1, 10, 4);
      case "eval" ->
          new Sizes(
              100,
              1_000_000,
              64,
              15,
              16,
              20,
              200,
              60,
              20,
              30,
              List.of(8, 32, 128),
              List.of("uniform", "hot"),
              5,
              50,
              20);
      default -> throw new IllegalArgumentException("unknown proof size: " + size);
    };
  }
}
