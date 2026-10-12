package tally.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ConfigurableApplicationContext;
import tally.ledger.testing.LedgerIT;
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
