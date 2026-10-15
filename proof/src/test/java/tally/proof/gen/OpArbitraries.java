package tally.proof.gen;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.RandomDistribution;
import net.jqwik.api.Tuple;
import tally.proof.harness.Sizes;

public final class OpArbitraries {

  private OpArbitraries() {}

  public static Arbitrary<Op> op() {
    Arbitrary<Long> amounts = Arbitraries.longs().between(1, 10_000);
    Arbitrary<Integer> indexes = Arbitraries.integers().between(0, 63);
    Arbitrary<Long> holdAmounts = Arbitraries.longs().between(1, 5_000);
    Arbitrary<Integer> ttlSeconds = Arbitraries.integers().between(60, 3_600);
    return Arbitraries.frequencyOf(
        Tuple.of(20, Combinators.combine(indexes, amounts).as(Op.Deposit::new)),
        Tuple.of(15, Combinators.combine(indexes, amounts).as(Op.Withdraw::new)),
        Tuple.of(30, Combinators.combine(indexes, indexes, amounts).as(Op.Transfer::new)),
        Tuple.of(8, indexes.map(Op.Reverse::new)),
        Tuple.of(12, indexes.map(Op.RetrySame::new)),
        Tuple.of(5, indexes.map(Op.RetryDifferent::new)),
        Tuple.of(4, Arbitraries.of(0L, -500L).map(Op.CreateAccount::new)),
        Tuple.of(3, indexes.map(Op.Freeze::new)),
        Tuple.of(3, indexes.map(Op.Close::new)),
        Tuple.of(10, Combinators.combine(indexes, holdAmounts, ttlSeconds).as(Op.PlaceHold::new)),
        Tuple.of(
            6,
            Combinators.combine(indexes, Arbitraries.longs().between(1, 6_000), indexes)
                .as(Op.CaptureHold::new)),
        Tuple.of(4, indexes.map(Op.ReleaseHold::new)),
        Tuple.of(6, Arbitraries.integers().between(1, 7_200).map(Op.AdvanceClock::new)),
        Tuple.of(
            4,
            Combinators.combine(
                    indexes, indexes, holdAmounts, Arbitraries.of(true, false), ttlSeconds)
                .as(Op.CreateSchedule::new)),
        Tuple.of(
            6,
            Combinators.combine(indexes, holdAmounts, Arbitraries.integers().between(0, 2))
                .as(Op.CreatePayment::new)));
  }

  public static Arbitrary<List<Op>> sequences() {
    return op().list()
        .ofMinSize(1)
        .ofMaxSize(Sizes.current().sequenceLength())
        .withSizeDistribution(RandomDistribution.uniform());
  }
}
