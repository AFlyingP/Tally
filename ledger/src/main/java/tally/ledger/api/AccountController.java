package tally.ledger.api;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tally.ledger.accounts.AccountService;
import tally.platform.security.Caller;
import tally.platform.web.RequestIdFilter;

@RestController
public class AccountController {

  private final AccountService accounts;

  public AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  @PostMapping("/v1/accounts")
  @PreAuthorize("hasAuthority('SCOPE_accounts:write')")
  @ResponseStatus(HttpStatus.CREATED)
  AccountDto.Account create(
      @AuthenticationPrincipal Jwt jwt, @RequestBody AccountDto.CreateAccount request)
      throws SQLException {
    return accounts.create(Caller.from(jwt), request, RequestIdFilter.current());
  }

  @GetMapping("/v1/accounts/{id}")
  @PreAuthorize("hasAuthority('SCOPE_accounts:read')")
  AccountDto.Account get(@PathVariable UUID id) throws SQLException {
    return accounts.get(id);
  }

  @GetMapping("/v1/accounts/{id}/balance")
  @PreAuthorize("hasAuthority('SCOPE_accounts:read')")
  AccountDto.Balance balance(
      @PathVariable UUID id, @RequestParam(name = "as_of", required = false) Instant asOf)
      throws SQLException {
    return accounts.balance(id, asOf);
  }

  @GetMapping("/v1/accounts/{id}/entries")
  @PreAuthorize("hasAuthority('SCOPE_accounts:read')")
  AccountDto.EntryPage entries(
      @PathVariable UUID id,
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @RequestParam(name = "cursor", required = false) String cursor)
      throws SQLException {
    return accounts.entries(id, limit, cursor);
  }
}
