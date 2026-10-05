package tally.ledger.api;

import java.sql.SQLException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
}
