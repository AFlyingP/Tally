package tally.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ConfigurableApplicationContext;
import tally.ledger.testing.LedgerIT;
import tally.platform.security.Role;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@TestMethodOrder(MethodOrderer.MethodName.class)
class OpenApiDriftIT extends LedgerIT {

  private static final Path DOCUMENT = Path.of("..", "api", "ledger-openapi.json");
  private static final String STALE = "api/ledger-openapi.json is stale, run: sh task.sh openapi";
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  @Test
  void committedDocumentMatchesTheCode() throws IOException {
    String document;
    try (ConfigurableApplicationContext context =
        startApp(Map.of("springdoc.api-docs.enabled", "true"))) {
      HttpResponse<String> response = send(request(url(context, "/v3/api-docs"), null).GET());
      assertThat(response.statusCode()).isEqualTo(200);
      document = normalize(response.body());
    }
    if (Boolean.getBoolean("openapi.write")) {
      Files.createDirectories(DOCUMENT.getParent());
      Files.writeString(DOCUMENT, document, StandardCharsets.UTF_8);
    } else {
      assertThat(DOCUMENT).withFailMessage(STALE).exists();
      assertThat(document)
          .withFailMessage(STALE)
          .isEqualTo(Files.readString(DOCUMENT, StandardCharsets.UTF_8));
    }
  }

  @Test
  void documentListsEveryEndpoint() throws IOException {
    JsonNode paths = JSON.readTree(Files.readString(DOCUMENT, StandardCharsets.UTF_8)).get("paths");
    assertThat(paths).isNotNull();
    for (String path :
        List.of(
            "/v1/accounts",
            "/v1/accounts/{id}",
            "/v1/accounts/{id}/balance",
            "/v1/accounts/{id}/entries",
            "/v1/accounts/{id}/freeze",
            "/v1/accounts/{id}/close",
            "/v1/deposits",
            "/v1/withdrawals",
            "/v1/transfers",
            "/v1/transactions/{id}",
            "/v1/transactions/{id}/reversal")) {
      assertThat(paths.has(path)).as("document contains %s", path).isTrue();
    }
  }

  @Test
  void documentPropertiesMatchResponses() throws IOException {
    JsonNode document = JSON.readTree(Files.readString(DOCUMENT, StandardCharsets.UTF_8));
    String operator = token(Role.OPERATOR);
    String reader = token(Role.SERVICE_DESK);

    HttpResponse<String> created = post("/v1/accounts", operator, "{\"currency\":\"EUR\"}");
    assertThat(created.statusCode()).isEqualTo(201);
    JsonNode account = JSON.readTree(created.body());
    assertProperties(responseSchema(document, "/v1/accounts", "post", "201"), account);
    String accountId = account.get("id").asString();

    HttpResponse<String> deposited =
        post(
            "/v1/deposits",
            operator,
            "{\"account_id\":\"%s\",\"amount\":500,\"currency\":\"EUR\"}".formatted(accountId),
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(deposited.statusCode()).isEqualTo(201);
    String transactionId = JSON.readTree(deposited.body()).get("id").asString();

    HttpResponse<String> balance = get("/v1/accounts/" + accountId + "/balance", reader);
    assertThat(balance.statusCode()).isEqualTo(200);
    assertProperties(
        responseSchema(document, "/v1/accounts/{id}/balance", "get", "200"),
        JSON.readTree(balance.body()));

    HttpResponse<String> entries = get("/v1/accounts/" + accountId + "/entries", reader);
    assertThat(entries.statusCode()).isEqualTo(200);
    JsonNode page = JSON.readTree(entries.body());
    JsonNode pageSchema = responseSchema(document, "/v1/accounts/{id}/entries", "get", "200");
    assertProperties(pageSchema, page);
    assertThat(page.get("items").size()).isEqualTo(1);
    JsonNode accountEntrySchema =
        resolveSchema(
            document,
            pageSchema.get("properties").get("items").get("items").get("$ref").asString());
    assertProperties(accountEntrySchema, page.get("items").get(0));

    HttpResponse<String> transaction = get("/v1/transactions/" + transactionId, reader);
    assertThat(transaction.statusCode()).isEqualTo(200);
    JsonNode transactionBody = JSON.readTree(transaction.body());
    JsonNode transactionSchema = responseSchema(document, "/v1/transactions/{id}", "get", "200");
    assertProperties(transactionSchema, transactionBody);
    assertThat(transactionBody.get("entries").size()).isPositive();
    JsonNode transactionEntrySchema =
        resolveSchema(
            document,
            transactionSchema.get("properties").get("entries").get("items").get("$ref").asString());
    assertProperties(transactionEntrySchema, transactionBody.get("entries").get(0));

    JsonNode schemas = document.get("components").get("schemas");
    for (String name : schemas.propertyNames()) {
      JsonNode schema = schemas.get(name);
      JsonNode properties = schema.get("properties");
      if (properties != null) {
        for (String property : properties.propertyNames()) {
          assertThat(property).as("property of %s", name).doesNotContainPattern("[A-Z]");
        }
      }
      JsonNode required = schema.get("required");
      if (required != null) {
        for (JsonNode property : required) {
          assertThat(property.asString())
              .as("required property of %s", name)
              .doesNotContainPattern("[A-Z]");
        }
      }
    }
  }

  private static JsonNode responseSchema(
      JsonNode document, String path, String method, String status) {
    JsonNode content =
        document.get("paths").get(path).get(method).get("responses").get(status).get("content");
    assertThat(content.isObject()).isTrue();
    assertThat(content.size()).isPositive();
    String mediaType = content.propertyNames().iterator().next();
    String reference = content.get(mediaType).get("schema").get("$ref").asString();
    return resolveSchema(document, reference);
  }

  private static JsonNode resolveSchema(JsonNode document, String reference) {
    assertThat(reference).startsWith("#/components/schemas/");
    JsonNode schema = document.at(reference.substring(1));
    assertThat(schema.isObject()).as("schema referenced by %s", reference).isTrue();
    return schema;
  }

  private static void assertProperties(JsonNode schema, JsonNode body) {
    assertThat(body.isObject()).isTrue();
    assertThat(schema.get("properties").propertyNames()).containsAll(body.propertyNames());
  }

  private static String normalize(String json) {
    Map<String, Object> document =
        JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
    document.remove("servers");
    DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
    DefaultPrettyPrinter printer =
        new DefaultPrettyPrinter().withObjectIndenter(indenter).withArrayIndenter(indenter);
    return JSON.writer().with(printer).writeValueAsString(document) + "\n";
  }
}
