package tally.platform.security;

import java.util.HashSet;
import java.util.Set;

/** The roles a client can have, each with a fixed set of scopes. */
public enum Role {
  SERVICE_DESK("service-desk", Set.of(Scopes.ACCOUNTS_READ, Scopes.TRANSACTIONS_READ)),
  OPERATOR(
      "operator",
      union(
          SERVICE_DESK.scopes,
          Scopes.ACCOUNTS_WRITE,
          Scopes.MONEY_WRITE,
          Scopes.HOLDS_READ,
          Scopes.HOLDS_WRITE,
          Scopes.SCHEDULES_READ,
          Scopes.SCHEDULES_WRITE,
          Scopes.PAYMENTS_READ,
          Scopes.PAYMENTS_WRITE)),
  ADMIN(
      "admin",
      union(
          OPERATOR.scopes,
          Scopes.TRANSACTIONS_REVERSE,
          Scopes.ACCOUNTS_ADMIN,
          Scopes.RECON_READ,
          Scopes.RECON_RUN,
          Scopes.CLIENTS_READ,
          Scopes.CLIENTS_MANAGE)),
  AUDITOR(
      "auditor",
      Set.of(
          Scopes.ACCOUNTS_READ,
          Scopes.TRANSACTIONS_READ,
          Scopes.HOLDS_READ,
          Scopes.SCHEDULES_READ,
          Scopes.PAYMENTS_READ,
          Scopes.RECON_READ,
          Scopes.AUDIT_READ,
          Scopes.CASES_READ,
          Scopes.CLIENTS_READ)),
  COMPLIANCE("compliance", union(AUDITOR.scopes, Scopes.CASES_WRITE));

  private final String wireName;
  private final Set<String> scopes;

  Role(String wireName, Set<String> scopes) {
    this.wireName = wireName;
    this.scopes = scopes;
  }

  public String wireName() {
    return wireName;
  }

  public Set<String> scopes() {
    return scopes;
  }

  public static Role fromWireName(String wireName) {
    for (Role role : values()) {
      if (role.wireName.equals(wireName)) {
        return role;
      }
    }
    throw new IllegalArgumentException("unknown role: " + wireName);
  }

  private static Set<String> union(Set<String> base, String... more) {
    Set<String> all = new HashSet<>(base);
    all.addAll(Set.of(more));
    return Set.copyOf(all);
  }
}
