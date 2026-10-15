package tally.monitor.api;

import java.sql.SQLException;
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
import tally.monitor.cases.CaseService;
import tally.platform.security.Caller;
import tally.platform.web.RequestIdFilter;

@RestController
public class CaseController {

  private final CaseService cases;

  public CaseController(CaseService cases) {
    this.cases = cases;
  }

  @GetMapping("/v1/cases")
  @PreAuthorize("hasAuthority('SCOPE_cases:read')")
  CaseDto.CasePage list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String rule,
      @RequestParam(name = "account_id", required = false) UUID accountId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String cursor)
      throws SQLException {
    return cases.list(status, rule, accountId, limit, cursor);
  }

  @GetMapping("/v1/cases/{id}")
  @PreAuthorize("hasAuthority('SCOPE_cases:read')")
  CaseDto.CaseDetail get(@PathVariable UUID id) throws SQLException {
    return cases.get(id);
  }

  @PostMapping("/v1/cases/{id}/assign")
  @PreAuthorize("hasAuthority('SCOPE_cases:write')")
  CaseDto.CaseDetail assign(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @jakarta.validation.Valid @RequestBody CaseDto.Assign request)
      throws SQLException {
    return cases.assign(Caller.from(jwt), id, request, RequestIdFilter.current());
  }

  @PostMapping("/v1/cases/{id}/notes")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('SCOPE_cases:write')")
  CaseDto.CaseDetail note(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @jakarta.validation.Valid @RequestBody CaseDto.Note request)
      throws SQLException {
    return cases.note(Caller.from(jwt), id, request, RequestIdFilter.current());
  }

  @PostMapping("/v1/cases/{id}/close")
  @PreAuthorize("hasAuthority('SCOPE_cases:write')")
  CaseDto.CaseDetail close(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @jakarta.validation.Valid @RequestBody CaseDto.Close request)
      throws SQLException {
    return cases.close(Caller.from(jwt), id, request, RequestIdFilter.current());
  }
}
