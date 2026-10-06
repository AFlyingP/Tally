package tally.platform.testing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import tally.platform.security.Role;

/** Signs access tokens for tests and serves the matching key set over HTTP. */
public final class TestIssuer implements AutoCloseable {

  private static final long LIFETIME_SECONDS = 900;

  private final RSAKey key;
  private final HttpServer server;

  private TestIssuer(RSAKey key, HttpServer server) {
    this.key = key;
    this.server = server;
  }

  public static TestIssuer start() {
    try {
      RSAKey key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
      byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      // Bound to all interfaces so that services running in containers can fetch the keys.
      HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
      server.createContext(
          "/jwks",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
          });
      server.start();
      return new TestIssuer(key, server);
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String jwksUri() {
    return "http://localhost:" + server.getAddress().getPort() + "/jwks";
  }

  public String issuer() {
    return "http://test-issuer";
  }

  public String token(Role role) {
    return token("test-" + role.wireName(), role);
  }

  public String token(String clientId, Role role) {
    return sign(clientId, role.wireName(), scopeClaim(role), Instant.now());
  }

  public String tokenWithScopes(String clientId, String... scopes) {
    return sign(clientId, null, String.join(" ", scopes), Instant.now());
  }

  public String expiredToken(Role role) {
    Instant issuedAt = Instant.now().minusSeconds(2 * LIFETIME_SECONDS);
    return sign("test-" + role.wireName(), role.wireName(), scopeClaim(role), issuedAt);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  // Sorted like the tokens of the auth service.
  private static String scopeClaim(Role role) {
    return String.join(" ", role.scopes().stream().sorted().toList());
  }

  private String sign(String clientId, String role, String scope, Instant issuedAt) {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer())
            .subject(clientId)
            .audience(List.of("tally"))
            .issueTime(Date.from(issuedAt))
            .notBeforeTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plusSeconds(LIFETIME_SECONDS)))
            .jwtID(UUID.randomUUID().toString())
            .claim("scope", scope);
    if (role != null) {
      claims.claim("role", role);
    }
    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(key.getKeyID())
            .type(JOSEObjectType.JWT)
            .build();
    SignedJWT jwt = new SignedJWT(header, claims.build());
    try {
      jwt.sign(new RSASSASigner(key));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    return jwt.serialize();
  }
}
