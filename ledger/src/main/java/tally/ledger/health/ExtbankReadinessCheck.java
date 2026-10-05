package tally.ledger.health;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tally.ledger.config.LedgerProperties;
import tally.platform.web.ReadinessCheck;

@Component
@ConditionalOnExpression(
    "'${tally.ledger.roles}'.contains('orchestrator') or '${tally.ledger.roles}'.contains('recon')")
public class ExtbankReadinessCheck implements ReadinessCheck {

  private static final Duration TIMEOUT = Duration.ofMillis(1000);

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final HttpRequest request;

  public ExtbankReadinessCheck(LedgerProperties properties) {
    this.request =
        HttpRequest.newBuilder(URI.create(properties.extbank().url() + "/health"))
            .timeout(TIMEOUT)
            .build();
  }

  @Override
  public String name() {
    return "extbank";
  }

  @Override
  public boolean up() {
    try {
      return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (IOException e) {
      return false;
    }
  }
}
