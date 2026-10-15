package tally.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tally.platform.security.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@TestMethodOrder(OrderAnnotation.class)
class ClientApiIT {

  private static final String PASSWORD = "test-password";
  private static final String ISSUER = "http://auth.test:8081";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @TempDir static Path tempDir;
  private static PostgreSQLContainer postgres;
  private static ConfigurableApplicationContext app;
  private static int port;

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
    port = freePort();
    app = startApp();
  }

  @AfterAll
  static void stop() {
    if (app != null) {
      app.close();
    }
    if (postgres != null) {
      postgres.stop();
    }
  }

  @Test
  @Order(1)
  void bootstrapClientsExistAfterStart() throws Exception {
    HttpResponse<String> response = get("/v1/clients", token(Role.ADMIN));
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode items = JSON.readTree(response.body()).get("items");
    assertThat(items.size()).isEqualTo(5);
    assertThat(queryLong("SELECT count(*) FROM auth.client_event WHERE type = 'BOOTSTRAPPED'"))
        .isEqualTo(5);

    app.close();
    app = startApp();
    assertThat(JSON.readTree(get("/v1/clients", token(Role.ADMIN)).body()).get("items").size())
        .isEqualTo(5);
    assertThat(queryLong("SELECT count(*) FROM auth.client_event WHERE type = 'BOOTSTRAPPED'"))
        .isEqualTo(5);
  }

  @Test
  @Order(2)
  void createdClientCanGetTokenWithRoleScopes() throws Exception {
    JsonNode created = create("branch-12", "service-desk", token(Role.ADMIN));
    assertThat(created.get("client_secret").asString()).hasSize(48);
    HttpResponse<String> response =
        tokenRequest("branch-12", created.get("client_secret").asString());
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode token = JSON.readTree(response.body());
    com.nimbusds.jwt.SignedJWT jwt =
        com.nimbusds.jwt.SignedJWT.parse(token.get("access_token").asString());
    assertThat(jwt.getJWTClaimsSet().getStringClaim("scope"))
        .isEqualTo("accounts:read transactions:read");
    assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("service-desk");
  }

  @Test
  @Order(3)
  void duplicateClientIsRejected() throws Exception {
    String admin = token(Role.ADMIN);
    assertThat(createResponse("branch-duplicate", "service-desk", admin).statusCode())
        .isEqualTo(201);
    HttpResponse<String> duplicate =
        post(
            "/v1/clients", admin, "{\"client_id\":\"branch-duplicate\",\"role\":\"service-desk\"}");
    assertThat(duplicate.statusCode()).isEqualTo(409);
    assertThat(errorCode(duplicate)).isEqualTo("CLIENT_EXISTS");
  }

  @Test
  @Order(4)
  void invalidClientIdAndRoleAreRejected() throws Exception {
    String admin = token(Role.ADMIN);
    HttpResponse<String> invalidId =
        post("/v1/clients", admin, "{\"client_id\":\"AB\",\"role\":\"admin\"}");
    assertThat(invalidId.statusCode()).isEqualTo(400);
    assertThat(errorCode(invalidId)).isEqualTo("VALIDATION_FAILED");
    HttpResponse<String> invalidRole =
        post("/v1/clients", admin, "{\"client_id\":\"branch-invalid\",\"role\":\"root\"}");
    assertThat(invalidRole.statusCode()).isEqualTo(400);
    assertThat(errorCode(invalidRole)).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  @Order(5)
  void disabledClientCannotGetToken() throws Exception {
    String admin = token(Role.ADMIN);
    JsonNode created = create("branch-disable", "service-desk", admin);
    String secret = created.get("client_secret").asString();
    assertThat(delete("/v1/clients/branch-disable", admin).statusCode()).isEqualTo(204);
    HttpResponse<String> token = tokenRequest("branch-disable", secret);
    assertThat(token.statusCode()).isEqualTo(401);
    assertThat(JSON.readTree(token.body()).get("error").asString()).isEqualTo("invalid_client");
    JsonNode listed = JSON.readTree(get("/v1/clients", admin).body()).get("items");
    JsonNode disabled = null;
    for (JsonNode item : listed) {
      if (item.get("client_id").asString().equals("branch-disable")) {
        disabled = item;
      }
    }
    assertThat(disabled).isNotNull();
    assertThat(disabled.get("disabled_at").isNull()).isFalse();
    long events =
        queryLong("SELECT count(*) FROM auth.client_event WHERE client_id = 'branch-disable'");
    assertThat(delete("/v1/clients/branch-disable", admin).statusCode()).isEqualTo(204);
    assertThat(
            queryLong("SELECT count(*) FROM auth.client_event WHERE client_id = 'branch-disable'"))
        .isEqualTo(events);
    HttpResponse<String> missing = delete("/v1/clients/nope-1", admin);
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(errorCode(missing)).isEqualTo("NOT_FOUND");
  }

  @Test
  @Order(6)
  void secretIsStoredHashed() throws Exception {
    JsonNode created = create("branch-hash", "service-desk", token(Role.ADMIN));
    String secret = created.get("client_secret").asString();
    String hash =
        queryString("SELECT secret_hash FROM auth.api_client WHERE client_id = 'branch-hash'");
    assertThat(hash).startsWith("$2").isNotEqualTo(secret);
  }

  @Test
  @Order(7)
  void scopesAreEnforced() throws Exception {
    String operator = token(Role.OPERATOR);
    assertThat(
            post("/v1/clients", operator, "{\"client_id\":\"branch-denied\",\"role\":\"admin\"}")
                .statusCode())
        .isEqualTo(403);
    assertThat(get("/v1/clients", operator).statusCode()).isEqualTo(403);
    assertThat(delete("/v1/clients/tally-admin", operator).statusCode()).isEqualTo(403);
    String auditor = token(Role.AUDITOR);
    assertThat(get("/v1/clients", auditor).statusCode()).isEqualTo(200);
    assertThat(
            post("/v1/clients", auditor, "{\"client_id\":\"branch-denied\",\"role\":\"admin\"}")
                .statusCode())
        .isEqualTo(403);
    HttpResponse<String> anonymous = get("/v1/clients", null);
    assertThat(anonymous.statusCode()).isEqualTo(401);
    JsonNode error = JSON.readTree(anonymous.body()).get("error");
    assertThat(error.get("code").asString()).isEqualTo("UNAUTHENTICATED");
    assertThat(error.has("message")).isTrue();
    assertThat(error.has("request_id")).isTrue();
    assertThat(error.get("details").isArray()).isTrue();
  }

  @Test
  @Order(8)
  void clientEventsAreAppendOnly() throws Exception {
    boolean rejected = false;
    try (Connection connection = superJdbc();
        PreparedStatement statement =
            connection.prepareStatement("UPDATE auth.client_event SET actor = 'x'")) {
      statement.executeUpdate();
    } catch (SQLException e) {
      rejected = true;
      assertThat(e.getSQLState()).isEqualTo("TL001");
    }
    assertThat(rejected).isTrue();
  }

  @Test
  @Order(9)
  void createWritesEventWithActor() throws Exception {
    create("branch-event", "service-desk", token(Role.ADMIN));
    assertThat(
            queryLong(
                "SELECT count(*) FROM auth.client_event WHERE client_id = 'branch-event' "
                    + "AND type = 'CREATED' AND actor = 'tally-admin'"))
        .isEqualTo(1);
  }

  private static ConfigurableApplicationContext startApp() {
    List<String> args = new ArrayList<>();
    args.add("--server.port=" + port);
    args.add("--spring.datasource.url=" + postgres.getJdbcUrl());
    args.add("--spring.datasource.password=" + PASSWORD);
    args.add("--tally.auth.issuer=" + ISSUER);
    args.add("--tally.auth.jwks-uri=http://localhost:" + port + "/oauth2/jwks");
    args.add("--tally.auth.key-path=" + tempDir.resolve("signing-key.pem"));
    for (Role role : Role.values()) {
      String key = "--tally.auth.clients." + role.wireName();
      args.add(key + ".id=" + clientId(role));
      args.add(key + ".secret=" + secret(role));
    }
    return AuthApplication.builder().run(args.toArray(String[]::new));
  }

  private static JsonNode create(String clientId, String role, String token) throws Exception {
    HttpResponse<String> response = createResponse(clientId, role, token);
    assertThat(response.statusCode()).isEqualTo(201);
    return JSON.readTree(response.body());
  }

  private static HttpResponse<String> createResponse(String clientId, String role, String token) {
    return post(
        "/v1/clients", token, "{\"client_id\":\"" + clientId + "\",\"role\":\"" + role + "\"}");
  }

  private static String token(Role role) throws Exception {
    HttpResponse<String> response = tokenRequest(clientId(role), secret(role));
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body()).get("access_token").asString();
  }

  private static HttpResponse<String> tokenRequest(String clientId, String secret) {
    String basic =
        Base64.getEncoder()
            .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    return send(
        HttpRequest.newBuilder(uri("/oauth2/token"))
            .header("Authorization", "Basic " + basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")));
  }

  private static HttpResponse<String> get(String path, String token) {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return send(request);
  }

  private static HttpResponse<String> post(String path, String token, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return send(request);
  }

  private static HttpResponse<String> delete(String path, String token) {
    return send(
        HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).DELETE());
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
    return URI.create("http://localhost:" + port + path);
  }

  private static String errorCode(HttpResponse<String> response) throws Exception {
    return JSON.readTree(response.body()).get("error").get("code").asString();
  }

  private static long queryLong(String sql) throws SQLException {
    try (Connection connection = superJdbc();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getLong(1);
    }
  }

  private static String queryString(String sql) throws SQLException {
    try (Connection connection = superJdbc();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      result.next();
      return result.getString(1);
    }
  }

  private static Connection superJdbc() throws SQLException {
    return DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", PASSWORD);
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String clientId(Role role) {
    return "tally-" + role.wireName();
  }

  private static String secret(Role role) {
    return "test-secret-for-" + role.wireName() + "-0123456789abcdef";
  }
}
