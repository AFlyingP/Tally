package tally.proof.harness;

import java.net.URI;
import java.net.http.HttpRequest;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import tally.extbank.ExtbankApplication;
import tools.jackson.databind.json.JsonMapper;

public final class InProcessExtbank {

  private static final String API_KEY = "k".repeat(48);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ConfigurableApplicationContext context;

  private InProcessExtbank(ConfigurableApplicationContext context) {
    this.context = context;
  }

  public static InProcessExtbank start(Clock clock) {
    Postgres postgres = Postgres.shared();
    try {
      postgres.reset();
    } catch (SQLException e) {
      throw new IllegalStateException("failed to reset database", e);
    }
    ConfigurableApplicationContext context =
        ExtbankApplication.builder()
            .initializers(
                initializer ->
                    ((GenericApplicationContext) initializer)
                        .registerBean(Clock.class, () -> clock))
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.jdbcUrl(),
                "--spring.datasource.username=extbank_app",
                "--spring.datasource.password=test-password",
                "--spring.datasource.hikari.maximum-pool-size=2",
                "--tally.extbank.api-key=" + API_KEY);
    return new InProcessExtbank(context);
  }

  public String url() {
    return "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
  }

  public String apiKey() {
    return API_KEY;
  }

  public void rule(String creditorAccount, String behaviour) {
    Map<String, Object> body = new HashMap<>();
    body.put("creditor_account", creditorAccount);
    body.put("behaviour", behaviour);
    Http.Response response =
        Http.post(
            url() + "/control/rules", null, JSON.writeValueAsString(body), "X-Api-Key", API_KEY);
    if (response.status() != 201) {
      throw new IllegalStateException("rule request failed with status " + response.status());
    }
  }

  public void clearRules() {
    Http.Response response =
        Http.send(
            HttpRequest.newBuilder(URI.create(url() + "/control/rules"))
                .header("X-Api-Key", API_KEY)
                .DELETE());
    if (response.status() != 204) {
      throw new IllegalStateException(
          "clear rules request failed with status " + response.status());
    }
  }

  public void stop() {
    context.close();
  }
}
