package tally.proof.harness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A small JSON HTTP client for tests that talk to running services. */
public final class Http {

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration TIMEOUT = Duration.ofMillis(5000);

  private Http() {}

  public record Response(int status, String body, Map<String, List<String>> headers) {

    public JsonNode json() {
      return JSON.readTree(body);
    }

    public String header(String name) {
      return headers.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(name))
          .map(e -> e.getValue().get(0))
          .findFirst()
          .orElse(null);
    }
  }

  public static Response get(String url, String bearerOrNull) {
    return send(request(url, bearerOrNull).GET());
  }

  public static Response post(
      String url, String bearerOrNull, String jsonBody, String... headerPairs) {
    HttpRequest.Builder request =
        request(url, bearerOrNull)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
    if (headerPairs.length > 0) {
      request.headers(headerPairs);
    }
    return send(request);
  }

  static Response send(HttpRequest.Builder request) {
    try {
      HttpResponse<String> response =
          CLIENT.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body(), response.headers().map());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static HttpRequest.Builder request(String url, String bearerOrNull) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
    if (bearerOrNull != null) {
      request.header("Authorization", "Bearer " + bearerOrNull);
    }
    return request;
  }
}
