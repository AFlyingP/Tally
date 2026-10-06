package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class AmountsProperties {

  private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

  @Property(tries = 1000, seed = "20260101")
  void addMatchesBigIntegerOrOverflows(@ForAll long a, @ForAll long b) {
    BigInteger sum = BigInteger.valueOf(a).add(BigInteger.valueOf(b));

    if (sum.compareTo(LONG_MIN) < 0 || sum.compareTo(LONG_MAX) > 0) {
      assertThatThrownBy(() -> Amounts.add(a, b))
          .isInstanceOfSatisfying(
              LedgerRuleException.class,
              exception -> assertThat(exception.code()).isEqualTo(RuleCode.AMOUNT_OVERFLOW));
    } else {
      assertThat(Amounts.add(a, b)).isEqualTo(sum.longValueExact());
    }
  }

  @Property(seed = "20260101")
  void balancedPostingsAlwaysValidate(@ForAll("postingAmounts") List<Long> amounts) {
    List<EntryLine> lines = new ArrayList<>();
    long sum = 0;
    for (int i = 0; i < amounts.size(); i++) {
      long amount = amounts.get(i);
      sum = Amounts.add(sum, amount);
      lines.add(new EntryLine(new UUID(0, i + 1L), Side.DEBIT, amount));
    }
    lines.add(new EntryLine(new UUID(1, 0), Side.CREDIT, sum));

    Posting posting = Posting.of("USD", lines);

    assertThat(posting.amount()).isEqualTo(sum);
    assertThat(posting.lines()).containsExactlyElementsOf(lines);
  }

  @Provide
  Arbitrary<List<Long>> postingAmounts() {
    return Arbitraries.longs().between(1, 1_000_000_000_000_000L).list().ofMinSize(1).ofMaxSize(10);
  }
}
