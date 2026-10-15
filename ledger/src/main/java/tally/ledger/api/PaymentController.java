package tally.ledger.api;

import jakarta.validation.Valid;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tally.ledger.idempotency.IdempotentExecutor.Result;
import tally.ledger.payments.PaymentService;
import tally.platform.security.Caller;

@RestController
public class PaymentController {

  private final PaymentService payments;

  public PaymentController(PaymentService payments) {
    this.payments = payments;
  }

  @PostMapping("/v1/external-payments")
  @PreAuthorize("hasAuthority('SCOPE_payments:write')")
  ResponseEntity<String> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody PaymentDto.Create request) {
    return response(payments.create(Caller.from(jwt), key, request));
  }

  @GetMapping("/v1/external-payments/{id}")
  @PreAuthorize("hasAuthority('SCOPE_payments:read')")
  PaymentDto.Payment get(@PathVariable UUID id) throws SQLException {
    return payments.get(id);
  }

  private static ResponseEntity<String> response(Result result) {
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotent-Replay", Boolean.toString(result.replay()))
        .header("X-Tally-Attempts", Integer.toString(result.attempts()))
        .body(result.body());
  }
}
