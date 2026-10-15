package tally.ledger.payments;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tally.ledger.config.LedgerProperties;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ExtbankClient {

  public sealed interface SubmitResult permits Confirmed, Rejected, Pending, Conflict, Unknown {}

  public sealed interface StatusResult permits Confirmed, Rejected, Pending, NotFound, Unknown {}

  public record Confirmed(LocalDate valueDate) implements SubmitResult, StatusResult {}

  public record Rejected(String reason) implements SubmitResult, StatusResult {}

  public record Pending() implements SubmitResult, StatusResult {}

  public record Conflict() implements SubmitResult {}

  public record NotFound() implements StatusResult {}

  public record Unknown(String cause) implements SubmitResult, StatusResult {}

  private final RestClient client;
  private final RestClient statementClient;
  private final JsonMapper mapper;
  private final String apiKey;

  public ExtbankClient(LedgerProperties properties, JsonMapper mapper) {
    this.mapper = mapper;
    this.apiKey = properties.extbank().apiKey();
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1000)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofMillis(properties.extbank().timeoutMs()));
    this.client =
        RestClient.builder()
            .baseUrl(properties.extbank().url())
            .requestFactory(requestFactory)
            .build();
    JdkClientHttpRequestFactory statementFactory = new JdkClientHttpRequestFactory(httpClient);
    statementFactory.setReadTimeout(Duration.ofMillis(30000));
    this.statementClient =
        RestClient.builder()
            .baseUrl(properties.extbank().url())
            .requestFactory(statementFactory)
            .build();
  }

  public String statement(LocalDate date, String requestId) {
    try {
      return statementClient
          .get()
          .uri("/statements/{date}", date)
          .header("X-Api-Key", apiKey)
          .header("X-Request-Id", requestId)
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                if (status != 200) {
                  throw new ApiException(
                      ErrorCode.STATEMENT_UNAVAILABLE, "statement fetch failed: http " + status);
                }
                return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
              });
    } catch (RestClientException exception) {
      throw new ApiException(
          ErrorCode.STATEMENT_UNAVAILABLE, "statement fetch failed: " + exception.getMessage());
    }
  }

  public SubmitResult submit(
      String reference, String creditorAccount, long amount, String currency, String requestId) {
    try {
      return client
          .post()
          .uri("/payments")
          .header("X-Api-Key", apiKey)
          .header("X-Request-Id", requestId)
          .body(
              Map.of(
                  "reference", reference,
                  "creditor_account", creditorAccount,
                  "amount", amount,
                  "currency", currency))
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 409) {
                  return new Conflict();
                }
                if (status == 400) {
                  JsonNode body = mapper.readTree(response.getBody());
                  return new Rejected(
                      "invalid request: " + body.path("error").path("message").asString());
                }
                if (status != 200) {
                  return new Unknown("http " + status);
                }
                return submitResult(mapper.readTree(response.getBody()));
              });
    } catch (Exception exception) {
      return new Unknown(exception.getClass().getSimpleName());
    }
  }

  public StatusResult status(String reference, String requestId) {
    try {
      return client
          .get()
          .uri("/payments/{reference}", reference)
          .header("X-Api-Key", apiKey)
          .header("X-Request-Id", requestId)
          .exchange(
              (request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 404) {
                  return new NotFound();
                }
                if (status != 200) {
                  return new Unknown("http " + status);
                }
                return statusResult(mapper.readTree(response.getBody()));
              });
    } catch (Exception exception) {
      return new Unknown(exception.getClass().getSimpleName());
    }
  }

  private static SubmitResult submitResult(JsonNode body) {
    return switch (body.path("status").asString()) {
      case "CONFIRMED" -> new Confirmed(LocalDate.parse(body.path("value_date").asString()));
      case "REJECTED" -> new Rejected(body.path("reject_reason").asString());
      case "PENDING" -> new Pending();
      default -> new Unknown("http 200");
    };
  }

  private static StatusResult statusResult(JsonNode body) {
    return switch (body.path("status").asString()) {
      case "CONFIRMED" -> new Confirmed(LocalDate.parse(body.path("value_date").asString()));
      case "REJECTED" -> new Rejected(body.path("reject_reason").asString());
      case "PENDING" -> new Pending();
      default -> new Unknown("http 200");
    };
  }
}
