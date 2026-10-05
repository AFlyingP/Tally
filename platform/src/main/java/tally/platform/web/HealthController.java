package tally.platform.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

  private static final String UP = "UP";
  private static final String DOWN = "DOWN";

  private final ObjectProvider<ReadinessCheck> checks;

  public HealthController(ObjectProvider<ReadinessCheck> checks) {
    this.checks = checks;
  }

  public record Health(String status) {}

  public record Ready(String status, Map<String, String> checks) {}

  @GetMapping("/health")
  Health health() {
    return new Health(UP);
  }

  @GetMapping("/ready")
  ResponseEntity<Ready> ready() {
    Map<String, String> results = new LinkedHashMap<>();
    checks.orderedStream().forEach(check -> results.put(check.name(), check.up() ? UP : DOWN));
    boolean allUp = !results.containsValue(DOWN);
    return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
        .body(new Ready(allUp ? UP : DOWN, results));
  }
}
