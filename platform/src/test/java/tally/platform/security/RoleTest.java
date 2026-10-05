package tally.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoleTest {

  @Test
  void scopeSetsMatchTheTable() {
    assertThat(Role.SERVICE_DESK.scopes())
        .containsExactlyInAnyOrder("accounts:read", "transactions:read");
    assertThat(Role.OPERATOR.scopes())
        .containsExactlyInAnyOrder(
            "accounts:read",
            "transactions:read",
            "accounts:write",
            "money:write",
            "holds:read",
            "holds:write",
            "schedules:read",
            "schedules:write",
            "payments:read",
            "payments:write");
    assertThat(Role.ADMIN.scopes())
        .containsExactlyInAnyOrder(
            "accounts:read",
            "transactions:read",
            "accounts:write",
            "money:write",
            "holds:read",
            "holds:write",
            "schedules:read",
            "schedules:write",
            "payments:read",
            "payments:write",
            "transactions:reverse",
            "accounts:admin",
            "recon:read",
            "recon:run",
            "clients:read",
            "clients:manage");
    assertThat(Role.AUDITOR.scopes())
        .containsExactlyInAnyOrder(
            "accounts:read",
            "transactions:read",
            "holds:read",
            "schedules:read",
            "payments:read",
            "recon:read",
            "audit:read",
            "cases:read",
            "clients:read");
    assertThat(Role.COMPLIANCE.scopes())
        .containsExactlyInAnyOrder(
            "accounts:read",
            "transactions:read",
            "holds:read",
            "schedules:read",
            "payments:read",
            "recon:read",
            "audit:read",
            "cases:read",
            "clients:read",
            "cases:write");

    assertThat(Role.SERVICE_DESK.scopes()).hasSize(2);
    assertThat(Role.OPERATOR.scopes()).hasSize(10);
    assertThat(Role.ADMIN.scopes()).hasSize(16);
    assertThat(Role.AUDITOR.scopes()).hasSize(9);
    assertThat(Role.COMPLIANCE.scopes()).hasSize(10);
  }

  @Test
  void wireNamesRoundTrip() {
    for (Role role : Role.values()) {
      assertThat(Role.fromWireName(role.wireName())).isEqualTo(role);
    }
    assertThat(Role.SERVICE_DESK.wireName()).isEqualTo("service-desk");
    assertThatThrownBy(() -> Role.fromWireName("teller"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jobCallerHasNoRoleAndNoScopes() {
    Caller caller = Caller.job("scheduler");

    assertThat(caller.clientId()).isEqualTo("job:scheduler");
    assertThat(caller.role()).isNull();
    assertThat(caller.scopes()).isEmpty();
  }
}
