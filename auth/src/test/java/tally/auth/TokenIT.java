package tally.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tally.platform.security.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
class TokenIT {

  private static final String PASSWORD = "test-password";
  private static final String ISSUER = "http://auth.test:8081";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static PostgreSQLContainer postgres;
  private static ConfigurableApplicationContext app;

  @BeforeAll
  static void start() {
    postgres =
        new PostgreSQLContainer("postgres:16.15-alpine")
            .withDatabaseName("tally")
            .withUsername("postgres")
            .withPassword(PASSWORD)
            .withEnv("LEDGER_OWNER_PASSWORD", PASSWORD)
            .withEnv("LEDGER_APP_PASSWORD", PASSWORD)
            .withEnv("MONITOR_DB_PASSWORD", PASSWORD)
            .withEnv("AUTH_DB_PASSWORD", PASSWORD)
            .withEnv("EXTBANK_DB_PASSWORD", PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forHostPath("../deploy/postgres/init.sh", 0755),
                "/docker-entrypoint-initdb.d/init.sh");
    postgres.start();
    List<String> args = new ArrayList<>();
    args.add("--server.port=0");
    args.add("--spring.datasource.url=" + postgres.getJdbcUrl());
    args.add("--spring.datasource.password=" + PASSWORD);
    args.add("--tally.auth.issuer=" + ISSUER);
    for (Role role : Role.values()) {
      String key = "--tally.auth.clients." + role.wireName();
      args.add(key + ".id=" + clientId(role));
      args.add(key + ".secret=" + secret(role));
    }
    app = AuthApplication.builder().run(args.toArray(String[]::new));
  }

  @AfterAll
  static void stop() {
    app.close();
    postgres.stop();
  }

  @Test
  void issuesTokenForEachRole() throws Exception {
    for (Role role : Role.values()) {
      HttpResponse<String> response = tokenRequest(role, secret(role), "client_credentials");
      assertThat(response.statusCode()).isEqualTo(200);
      JsonNode body = JSON.readTree(response.body());
      assertThat(body.get("token_type").asString()).isEqualTo("Bearer");
      assertThat(body.get("expires_in").asInt()).isBetween(890, 900);
      assertThat(body.has("scope")).isFalse();

      SignedJWT jwt = SignedJWT.parse(body.get("access_token").asString());
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
      assertThat(jwt.getHeader().getType().getType()).isEqualTo("JWT");
      assertThat(claims.getIssuer()).isEqualTo(ISSUER);
      assertThat(claims.getAudience()).containsExactly("tally");
      assertThat(claims.getSubject()).isEqualTo(clientId(role));
      assertThat(claims.getStringClaim("role")).isEqualTo(role.wireName());
      assertThat(claims.getStringClaim("scope"))
          .isEqualTo(String.join(" ", role.scopes().stream().sorted().toList()));
      assertThat(claims.getJWTID()).isNotBlank();
      assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime())
          .isEqualTo(900_000);
    }
  }

  @Test
  void tokenVerifiesAgainstPublishedKey() throws Exception {
    HttpResponse<String> response =
        tokenRequest(Role.OPERATOR, secret(Role.OPERATOR), "client_credentials");
    SignedJWT jwt = SignedJWT.parse(JSON.readTree(response.body()).get("access_token").asString());

    HttpResponse<String> jwks = send(HttpRequest.newBuilder(uri("/oauth2/jwks")).GET());
    assertThat(jwks.statusCode()).isEqualTo(200);
    JWKSet keys = JWKSet.parse(jwks.body());
    assertThat(keys.getKeys()).hasSize(1);
    RSAKey key = (RSAKey) keys.getKeyByKeyId(jwt.getHeader().getKeyID());

    assertThat(key.getKeyID()).matches("[0-9a-f]{16}");
    assertThat(key.getKeyUse().identifier()).isEqualTo("sig");
    assertThat(key.getAlgorithm().getName()).isEqualTo("RS256");
    assertThat(key.isPrivate()).isFalse();
    assertThat(jwt.verify(new RSASSAVerifier(key))).isTrue();
  }

  @Test
  void rejectsWrongSecret() {
    HttpResponse<String> response =
        tokenRequest(Role.OPERATOR, "not-the-secret-not-the-secret-not-the", "client_credentials");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(JSON.readTree(response.body()).get("error").asString()).isEqualTo("invalid_client");
  }

  @Test
  void rejectsOtherGrantType() {
    HttpResponse<String> response = tokenRequest(Role.OPERATOR, secret(Role.OPERATOR), "password");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(response.body()).get("error").asString())
        .isEqualTo("unsupported_grant_type");
  }

  @Test
  void healthIsOpenAndOtherPathsAreNot() {
    HttpResponse<String> health = send(HttpRequest.newBuilder(uri("/health")).GET());
    assertThat(health.statusCode()).isEqualTo(200);

    HttpResponse<String> ready = send(HttpRequest.newBuilder(uri("/ready")).GET());
    assertThat(ready.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(ready.body()).get("status").asString()).isEqualTo("UP");

    HttpResponse<String> clients = send(HttpRequest.newBuilder(uri("/v1/clients")).GET());
    assertThat(clients.statusCode()).isEqualTo(401);
    assertThat(JSON.readTree(clients.body()).get("error").get("code").asString())
        .isEqualTo("UNAUTHENTICATED");
  }

  private static HttpResponse<String> tokenRequest(Role role, String secret, String grantType) {
    String basic =
        Base64.getEncoder()
            .encodeToString((clientId(role) + ":" + secret).getBytes(StandardCharsets.UTF_8));
    return send(
        HttpRequest.newBuilder(uri("/oauth2/token"))
            .header("Authorization", "Basic " + basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=" + grantType)));
  }

  private static HttpResponse<String> send(HttpRequest.Builder request) {
    try {
      return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static URI uri(String path) {
    String port = app.getEnvironment().getProperty("local.server.port");
    return URI.create("http://localhost:" + port + path);
  }

  private static String clientId(Role role) {
    return "tally-" + role.wireName();
  }

  private static String secret(Role role) {
    return "test-secret-for-" + role.wireName() + "-0123456789abcdef";
  }
}
