package tally.proof.seeded;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tally.ledger.payments.PaymentOrchestrator;
import tally.platform.security.Role;
import tally.platform.testing.MutableClock;
import tally.proof.harness.Http;
import tally.proof.harness.InProcessExtbank;
import tally.proof.harness.InProcessLedger;
import tally.proof.harness.LedgerClient;
import tally.proof.harness.Seeds;
import tally.proof.harness.Sizes;
import tools.jackson.databind.JsonNode;

@Tag("seeded")
public class SeededReconTest {

  private static final LocalDate DATE = LocalDate.of(2026, 3, 10);
  private static final long PAYMENT_AMOUNT = 1000;

  @Test
  void findsEverySeededBreakWithItsType() {
    Seeds.print("recon");
    measure(Sizes.current(), Seeds.seed());
  }

  public static Map<String, Object> measure(Sizes sizes, long seed) {
    MutableClock clock = new MutableClock(Instant.parse("2026-03-10T12:00:00Z"));
    InProcessExtbank extbank = InProcessExtbank.start(clock);
    try {
      extbank.rule("SEED-ACCEPT", "ACCEPT");
      InProcessLedger ledger = InProcessLedger.start("pessimistic", clock, extbank);
      try {
        LedgerClient client = new LedgerClient(ledger.url(), ledger.issuer().token(Role.ADMIN));
        JsonNode account = response(client.createAccount(0, true), 201);
        UUID source = UUID.fromString(account.get("id").asString());
        int initialPayments = 12 * sizes.reconBreaksPerType();
        int totalPayments = initialPayments + 5 * sizes.reconCleanFiles();
        response(client.deposit("recon-funding", source, totalPayments * PAYMENT_AMOUNT), 201);
        settle(ledger, client, source, 0, initialPayments);
        String cleanCsv = statement(extbank);
        assertThat(cleanCsv.lines().count() - 1).isEqualTo(initialPayments);
        StatementGenerator.Seeded altered =
            StatementGenerator.alter(cleanCsv, sizes.reconBreaksPerType(), seed);
        int seeded = 6 * sizes.reconBreaksPerType();
        assertThat(altered.expected()).hasSize(seeded);
        JsonNode run = response(client.runReconciliation(DATE, altered.csv()), 201);
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        Map<String, String> actual = new LinkedHashMap<>();
        for (JsonNode item : run.get("breaks")) {
          String reference = item.get("reference").asString();
          assertThat(actual).doesNotContainKey(reference);
          actual.put(reference, item.get("type").asString());
        }
        assertThat(actual).hasSize(run.get("break_count").asInt());
        assertThat(actual.keySet().stream().toList()).isSorted();
        int correct = 0;
        int missed = 0;
        int wrongType = 0;
        for (Map.Entry<String, String> expected : altered.expected().entrySet()) {
          String type = actual.get(expected.getKey());
          if (type == null) {
            missed++;
          } else if (type.equals(expected.getValue())) {
            correct++;
          } else {
            wrongType++;
          }
        }
        int extra = 0;
        for (String reference : actual.keySet()) {
          if (!altered.expected().containsKey(reference)) {
            extra++;
          }
        }
        UUID runId = UUID.fromString(run.get("id").asString());
        assertThat(response(client.reconRun(runId), 200)).isEqualTo(run);
        JsonNode rerun = response(client.runReconciliation(DATE, altered.csv()), 200);
        assertThat(rerun.get("id").asString()).isEqualTo(runId.toString());
        int malformedLine = 3;
        JsonNode failed =
            response(
                client.runReconciliation(DATE, StatementGenerator.malform(cleanCsv, malformedLine)),
                201);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("failure_line").asInt()).isEqualTo(malformedLine);
        assertThat(failed.get("failure_reason").asString()).isEqualTo("invalid amount");
        assertThat(failed.get("break_count").asInt()).isZero();
        assertThat(failed.get("breaks").isEmpty()).isTrue();
        int falseBreaks = 0;
        for (int file = 0; file < sizes.reconCleanFiles(); file++) {
          int existingPayments = initialPayments + 5 * file;
          settle(ledger, client, source, existingPayments, 5);
          JsonNode clean = response(client.runReconciliation(DATE, statement(extbank)), 201);
          assertThat(clean.get("status").asString()).isEqualTo("COMPLETED");
          assertThat(clean.get("statement_lines").asInt()).isEqualTo(existingPayments + 5);
          assertThat(clean.get("ledger_payments").asInt()).isEqualTo(existingPayments + 5);
          assertThat(clean.get("breaks").size()).isEqualTo(clean.get("break_count").asInt());
          falseBreaks += clean.get("break_count").asInt();
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("seeded", seeded);
        counts.put("correct", correct);
        counts.put("missed", missed);
        counts.put("wrong_type", wrongType);
        counts.put("extra", extra);
        counts.put("clean_files", sizes.reconCleanFiles());
        counts.put("false_breaks", falseBreaks);
        System.out.println(
            "recon "
                + counts.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(" ")));
        assertThat(correct).as("correctly classified seeded breaks").isEqualTo(seeded);
        assertThat(missed).as("missed seeded breaks").isZero();
        assertThat(wrongType).as("incorrectly classified seeded breaks").isZero();
        assertThat(extra).as("unseeded breaks").isZero();
        assertThat(falseBreaks).as("breaks in clean files").isZero();
        return counts;
      } finally {
        ledger.stop();
      }
    } finally {
      extbank.stop();
    }
  }

  private static void settle(
      InProcessLedger ledger, LedgerClient client, UUID source, int first, int count) {
    PaymentOrchestrator orchestrator = ledger.bean(PaymentOrchestrator.class);
    for (int index = first; index < first + count; index++) {
      JsonNode payment =
          response(
              client.createPayment("recon-payment-" + index, source, PAYMENT_AMOUNT, "SEED-ACCEPT"),
              202);
      UUID id = UUID.fromString(payment.get("id").asString());
      for (int call = 0; call < 5 && !payment.get("state").asString().equals("SETTLED"); call++) {
        orchestrator.runOnce();
        payment = response(client.payment(id), 200);
      }
      assertThat(payment.get("state").asString()).as("payment %s state", id).isEqualTo("SETTLED");
      assertThat(payment.get("value_date").asString()).isEqualTo(DATE.toString());
    }
  }

  private static String statement(InProcessExtbank extbank) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(extbank.url() + "/statements/" + DATE))
            .header("X-Api-Key", extbank.apiKey())
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      return response.body();
    } catch (IOException e) {
      throw new IllegalStateException("statement fetch failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("statement fetch interrupted", e);
    }
  }

  private static JsonNode response(Http.Response response, int status) {
    assertThat(response.status()).as(response.body()).isEqualTo(status);
    return response.json();
  }
}
