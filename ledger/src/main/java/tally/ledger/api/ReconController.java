package tally.ledger.api;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tally.ledger.recon.ReconService;
import tally.platform.security.Caller;
import tally.platform.web.RequestIdFilter;

@RestController
public class ReconController {

  private final ReconService recon;

  public ReconController(ReconService recon) {
    this.recon = recon;
  }

  @PostMapping("/v1/reconciliation/runs")
  @PreAuthorize("hasAuthority('SCOPE_recon:run')")
  ResponseEntity<ReconDto.ReconRun> run(
      @AuthenticationPrincipal Jwt jwt, @RequestBody ReconDto.Run request) {
    ReconService.RunResult result =
        recon.run(
            Caller.from(jwt),
            request.businessDate(),
            request.statementCsv(),
            RequestIdFilter.current());
    return ResponseEntity.status(result.created() ? 201 : 200).body(result.run());
  }

  @GetMapping("/v1/reconciliation/runs")
  @PreAuthorize("hasAuthority('SCOPE_recon:read')")
  ReconDto.ReconRunList byDate(
      @RequestParam("business_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate businessDate) {
    return recon.byDate(businessDate);
  }

  @GetMapping("/v1/reconciliation/runs/{id}")
  @PreAuthorize("hasAuthority('SCOPE_recon:read')")
  ReconDto.ReconRun get(@PathVariable UUID id) {
    return recon.get(id);
  }
}
