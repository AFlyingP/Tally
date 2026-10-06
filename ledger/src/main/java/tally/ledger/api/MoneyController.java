package tally.ledger.api;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tally.ledger.idempotency.IdempotentExecutor.Result;
import tally.ledger.money.MoneyService;
import tally.platform.security.Caller;

@RestController
public class MoneyController {

  private final MoneyService money;

  public MoneyController(MoneyService money) {
    this.money = money;
  }

  @PostMapping("/v1/deposits")
  @PreAuthorize("hasAuthority('SCOPE_money:write')")
  ResponseEntity<String> deposit(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody MoneyDto.Deposit request) {
    return response(money.deposit(Caller.from(jwt), key, request));
  }

  @PostMapping("/v1/withdrawals")
  @PreAuthorize("hasAuthority('SCOPE_money:write')")
  ResponseEntity<String> withdraw(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody MoneyDto.Withdrawal request) {
    return response(money.withdraw(Caller.from(jwt), key, request));
  }

  @PostMapping("/v1/transfers")
  @PreAuthorize("hasAuthority('SCOPE_money:write')")
  ResponseEntity<String> transfer(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody MoneyDto.Transfer request) {
    return response(money.transfer(Caller.from(jwt), key, request));
  }

  private static ResponseEntity<String> response(Result result) {
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotent-Replay", Boolean.toString(result.replay()))
        .header("X-Tally-Attempts", Integer.toString(result.attempts()))
        .body(result.body());
  }
}
