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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tally.ledger.idempotency.IdempotentExecutor.Result;
import tally.ledger.schedule.ScheduleService;
import tally.platform.security.Caller;
import tally.platform.web.RequestIdFilter;

@RestController
public class ScheduleController {

  private final ScheduleService schedules;

  public ScheduleController(ScheduleService schedules) {
    this.schedules = schedules;
  }

  @PostMapping("/v1/schedules")
  @PreAuthorize("hasAuthority('SCOPE_schedules:write')")
  ResponseEntity<String> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody ScheduleDto.Create request) {
    return response(schedules.create(Caller.from(jwt), key, request));
  }

  @GetMapping("/v1/schedules/{id}")
  @PreAuthorize("hasAuthority('SCOPE_schedules:read')")
  ScheduleDto.Schedule get(@PathVariable UUID id) throws SQLException {
    return schedules.get(id);
  }

  @GetMapping("/v1/schedules/{id}/occurrences")
  @PreAuthorize("hasAuthority('SCOPE_schedules:read')")
  ScheduleDto.OccurrenceList occurrences(
      @PathVariable UUID id, @RequestParam(defaultValue = "100") int limit) throws SQLException {
    return schedules.occurrences(id, limit);
  }

  @PostMapping("/v1/schedules/{id}/cancel")
  @PreAuthorize("hasAuthority('SCOPE_schedules:write')")
  ScheduleDto.Schedule cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt)
      throws SQLException {
    return schedules.cancel(Caller.from(jwt), id, RequestIdFilter.current());
  }

  private static ResponseEntity<String> response(Result result) {
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotent-Replay", Boolean.toString(result.replay()))
        .header("X-Tally-Attempts", Integer.toString(result.attempts()))
        .body(result.body());
  }
}
