package tally.proof.harness;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class LedgerClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String baseUrl;
  private final String token;

  public LedgerClient(String baseUrl, String token) {
    this.baseUrl = baseUrl;
    this.token = token;
  }

  public Http.Response createAccount(long minBalance, boolean creditNormal) {
    return Http.post(
        baseUrl + "/v1/accounts",
        token,
        JSON.writeValueAsString(
            Map.of(
                "currency",
                "EUR",
                "min_balance",
                minBalance,
                "normal_side",
                creditNormal ? "CREDIT" : "DEBIT")));
  }

  public Http.Response deposit(String key, UUID account, long amount) {
    return Http.post(
        baseUrl + "/v1/deposits",
        token,
        JSON.writeValueAsString(Map.of("account_id", account, "amount", amount, "currency", "EUR")),
        "Idempotency-Key",
        key);
  }

  public Http.Response withdraw(String key, UUID account, long amount) {
    return Http.post(
        baseUrl + "/v1/withdrawals",
        token,
        JSON.writeValueAsString(Map.of("account_id", account, "amount", amount, "currency", "EUR")),
        "Idempotency-Key",
        key);
  }

  public Http.Response transfer(String key, UUID source, UUID destination, long amount) {
    return Http.post(
        baseUrl + "/v1/transfers",
        token,
        JSON.writeValueAsString(
            Map.of(
                "source_account_id", source,
                "destination_account_id", destination,
                "amount", amount,
                "currency", "EUR")),
        "Idempotency-Key",
        key);
  }

  public Http.Response reverse(String key, UUID transaction) {
    return Http.post(
        baseUrl + "/v1/transactions/" + transaction + "/reversal",
        token,
        "{}",
        "Idempotency-Key",
        key);
  }

  public Http.Response freeze(UUID account) {
    return Http.post(baseUrl + "/v1/accounts/" + account + "/freeze", token, "{}");
  }

  public Http.Response close(UUID account) {
    return Http.post(baseUrl + "/v1/accounts/" + account + "/close", token, "{}");
  }

  public Http.Response balance(UUID account) {
    return Http.get(baseUrl + "/v1/accounts/" + account + "/balance", token);
  }

  public List<JsonNode> allEntries(UUID account) {
    List<JsonNode> entries = new ArrayList<>();
    String path = baseUrl + "/v1/accounts/" + account + "/entries?limit=200";
    String pageUrl = path;
    while (true) {
      Http.Response response = Http.get(pageUrl, token);
      if (response.status() != 200) {
        throw new IllegalStateException("entries request failed with status " + response.status());
      }
      JsonNode page = response.json();
      page.get("items").forEach(entries::add);
      JsonNode cursor = page.get("next_cursor");
      if (cursor.isNull()) {
        return entries;
      }
      pageUrl = path + "&cursor=" + URLEncoder.encode(cursor.asString(), StandardCharsets.UTF_8);
    }
  }

  public Http.Response transaction(UUID transaction) {
    return Http.get(baseUrl + "/v1/transactions/" + transaction, token);
  }

  public Http.Response placeHold(String key, UUID account, long amount, Instant expiresAt) {
    return Http.post(
        baseUrl + "/v1/holds",
        token,
        JSON.writeValueAsString(
            Map.of(
                "account_id", account,
                "amount", amount,
                "currency", "EUR",
                "expires_at", expiresAt)),
        "Idempotency-Key",
        key);
  }

  public Http.Response captureHold(String key, UUID hold, long amount, UUID destination) {
    return Http.post(
        baseUrl + "/v1/holds/" + hold + "/capture",
        token,
        JSON.writeValueAsString(Map.of("amount", amount, "destination_account_id", destination)),
        "Idempotency-Key",
        key);
  }

  public Http.Response releaseHold(String key, UUID hold) {
    return Http.post(
        baseUrl + "/v1/holds/" + hold + "/release", token, "{}", "Idempotency-Key", key);
  }

  public Http.Response hold(UUID hold) {
    return Http.get(baseUrl + "/v1/holds/" + hold, token);
  }

  public Http.Response createSchedule(
      String key, UUID source, UUID destination, long amount, String frequency, Instant startAt) {
    return Http.post(
        baseUrl + "/v1/schedules",
        token,
        JSON.writeValueAsString(
            Map.of(
                "source_account_id",
                source,
                "destination_account_id",
                destination,
                "amount",
                amount,
                "currency",
                "EUR",
                "frequency",
                frequency,
                "start_at",
                startAt)),
        "Idempotency-Key",
        key);
  }

  public Http.Response occurrences(UUID schedule) {
    return Http.get(baseUrl + "/v1/schedules/" + schedule + "/occurrences", token);
  }

  public Http.Response cancelSchedule(UUID schedule) {
    return Http.post(baseUrl + "/v1/schedules/" + schedule + "/cancel", token, "{}");
  }

  public Http.Response createPayment(String key, UUID source, long amount, String creditorAccount) {
    return Http.post(
        baseUrl + "/v1/external-payments",
        token,
        JSON.writeValueAsString(
            Map.of(
                "source_account_id",
                source,
                "amount",
                amount,
                "currency",
                "EUR",
                "creditor_account",
                creditorAccount)),
        "Idempotency-Key",
        key);
  }

  public Http.Response payment(UUID payment) {
    return Http.get(baseUrl + "/v1/external-payments/" + payment, token);
  }

  public static String errorCode(Http.Response response) {
    if (response.status() >= 200 && response.status() < 300) {
      return null;
    }
    return response.json().get("error").get("code").asString();
  }
}
