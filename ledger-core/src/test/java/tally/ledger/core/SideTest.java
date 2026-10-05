package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SideTest {

  @Test
  void oppositeFlipsTheSide() {
    assertThat(Side.DEBIT.opposite()).isEqualTo(Side.CREDIT);
    assertThat(Side.CREDIT.opposite()).isEqualTo(Side.DEBIT);
  }

  // The names are stored as text and checked by database constraints, so they must not drift.
  @Test
  void storedNamesMatchTheSchema() {
    assertThat(AccountKind.values())
        .extracting(Enum::name)
        .containsExactly("CUSTOMER", "SETTLEMENT", "CLEARING");
    assertThat(AccountStatus.values())
        .extracting(Enum::name)
        .containsExactly("OPEN", "FROZEN", "CLOSED");
  }
}
