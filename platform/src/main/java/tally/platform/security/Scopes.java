package tally.platform.security;

public final class Scopes {

  public static final String ACCOUNTS_READ = "accounts:read";
  public static final String ACCOUNTS_WRITE = "accounts:write";
  public static final String ACCOUNTS_ADMIN = "accounts:admin";
  public static final String TRANSACTIONS_READ = "transactions:read";
  public static final String TRANSACTIONS_REVERSE = "transactions:reverse";
  public static final String MONEY_WRITE = "money:write";
  public static final String HOLDS_READ = "holds:read";
  public static final String HOLDS_WRITE = "holds:write";
  public static final String SCHEDULES_READ = "schedules:read";
  public static final String SCHEDULES_WRITE = "schedules:write";
  public static final String PAYMENTS_READ = "payments:read";
  public static final String PAYMENTS_WRITE = "payments:write";
  public static final String RECON_READ = "recon:read";
  public static final String RECON_RUN = "recon:run";
  public static final String AUDIT_READ = "audit:read";
  public static final String CASES_READ = "cases:read";
  public static final String CASES_WRITE = "cases:write";
  public static final String CLIENTS_READ = "clients:read";
  public static final String CLIENTS_MANAGE = "clients:manage";

  private Scopes() {}
}
