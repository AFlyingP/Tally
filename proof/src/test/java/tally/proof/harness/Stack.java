package tally.proof.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tally.platform.security.Role;

/** Addresses and access tokens of the compose stack running on this machine. */
public final class Stack {

  private static final Duration TOKEN_REUSE = Duration.ofMinutes(10);
  private static final Map<String, String> ENV = readEnv();
  private static final Map<Role, CachedToken> TOKENS = new ConcurrentHashMap<>();

  private Stack() {}

  private record CachedToken(String value, Instant fetchedAt) {}

  public static String url(int port, String path) {
    return "http://localhost:" + port + path;
  }

  public static String env(String name) {
    return ENV.get(name);
  }

  /** A token for the bootstrap client of the role, fetched from the auth service. */
  public static String token(Role role) {
    CachedToken cached = TOKENS.get(role);
    if (cached == null || cached.fetchedAt().plus(TOKEN_REUSE).isBefore(Instant.now())) {
      cached = new CachedToken(fetchToken(role), Instant.now());
      TOKENS.put(role, cached);
    }
    return cached.value();
  }

  private static String fetchToken(Role role) {
    String id = env("TALLY_CLIENT_" + role.name() + "_ID");
    String secret = env("TALLY_CLIENT_" + role.name() + "_SECRET");
    String basic =
        Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    Http.Response response =
        Http.send(
            HttpRequest.newBuilder(URI.create(url(8081, "/oauth2/token")))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")));
    if (response.status() != 200) {
      throw new IllegalStateException("token request failed with status " + response.status());
    }
    return response.json().get("access_token").asString();
  }

  private static Map<String, String> readEnv() {
    Path file = Path.of(System.getProperty("proof.root"), ".env");
    Map<String, String> values = new HashMap<>();
    try {
      for (String line : Files.readAllLines(file)) {
        int equals = line.indexOf('=');
        if (equals > 0 && !line.startsWith("#")) {
          values.put(line.substring(0, equals), line.substring(equals + 1));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Map.copyOf(values);
  }
}
