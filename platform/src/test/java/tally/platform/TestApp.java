package tally.platform;

import java.time.Instant;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.testing.TestIssuer;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class TestApp {

  public static final TestIssuer ISSUER = TestIssuer.start();

  /** Points the resource server at the shared test issuer, so all tests share one context. */
  public static void authProperties(DynamicPropertyRegistry registry) {
    registry.add("tally.auth.jwks-uri", ISSUER::jwksUri);
    registry.add("tally.auth.issuer", ISSUER::issuer);
  }

  public record Echo(long amount, Instant at) {}

  @RestController
  static class Endpoints {

    @GetMapping("/t/open-data")
    String openData() {
      return "ok";
    }

    @GetMapping("/t/scoped")
    @PreAuthorize("hasAuthority('SCOPE_accounts:read')")
    String scoped() {
      return "ok";
    }

    @PostMapping("/t/echo")
    Echo echo(@RequestBody Echo echo) {
      return echo;
    }

    @GetMapping("/t/fail/{code}")
    String fail(@PathVariable ErrorCode code) {
      throw new ApiException(code, "requested failure");
    }

    @GetMapping("/t/boom")
    String boom() {
      throw new IllegalStateException("boom");
    }
  }
}
