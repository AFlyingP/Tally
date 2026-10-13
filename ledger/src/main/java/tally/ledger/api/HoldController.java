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
import tally.ledger.holds.HoldService;
import tally.ledger.idempotency.IdempotentExecutor.Result;
import tally.platform.security.Caller;

@RestController
public class HoldController {

  private final HoldService holds;

  public HoldController(HoldService holds) {
    this.holds = holds;
  }

  @PostMapping("/v1/holds")
  @PreAuthorize("hasAuthority('SCOPE_holds:write')")
  ResponseEntity<String> place(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody HoldDto.Place request) {
    return response(holds.place(Caller.from(jwt), key, request));
  }

  @GetMapping("/v1/holds/{id}")
  @PreAuthorize("hasAuthority('SCOPE_holds:read')")
  HoldDto.Hold get(@PathVariable UUID id) throws SQLException {
    return holds.get(id);
  }

  @PostMapping("/v1/holds/{id}/capture")
  @PreAuthorize("hasAuthority('SCOPE_holds:write')")
  ResponseEntity<String> capture(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody HoldDto.Capture request)
      throws SQLException {
    return response(holds.capture(Caller.from(jwt), key, id, request));
  }

  @PostMapping("/v1/holds/{id}/release")
  @PreAuthorize("hasAuthority('SCOPE_holds:write')")
  ResponseEntity<String> release(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String key)
      throws SQLException {
    return response(holds.release(Caller.from(jwt), key, id));
  }

  private static ResponseEntity<String> response(Result result) {
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotent-Replay", Boolean.toString(result.replay()))
        .header("X-Tally-Attempts", Integer.toString(result.attempts()))
        .body(result.body());
  }
}
