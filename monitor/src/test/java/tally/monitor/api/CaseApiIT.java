package tally.monitor.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tally.monitor.cases.AlertStore;
import tally.monitor.cases.CaseAssigner;
import tally.monitor.rules.Rule;
import tally.monitor.rules.RuleEngine;
import tally.monitor.testing.MonitorIT;
import tally.platform.id.UuidV7;
import tally.platform.security.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CaseApiIT extends MonitorIT {

  private static final Instant BASE = Instant.parse("2026-03-10T00:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void listsOpenCasesOldestFirst() throws Exception {
    UUID accountId = newAccount();
    Fixture first = addAlert(accountId, Rule.R1, BASE);
    Fixture second = addAlert(accountId, Rule.R1, BASE.plusSeconds(172800));
    Fixture third = addAlert(accountId, Rule.R1, BASE.plusSeconds(345600));
    try (Connection connection = superJdbc();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE monitor.case_file SET opened_at = ? WHERE id = ?")) {
      update.setTimestamp(1, Timestamp.from(BASE));
      update.setObject(2, second.caseId());
      update.executeUpdate();
      update.setTimestamp(1, Timestamp.from(BASE.plusSeconds(1)));
      update.setObject(2, third.caseId());
      update.executeUpdate();
      update.setTimestamp(1, Timestamp.from(BASE.plusSeconds(2)));
      update.setObject(2, first.caseId());
      update.executeUpdate();
    }

    JsonNode items =
        json(get("/v1/cases?status=OPEN&account_id=" + accountId, token(Role.COMPLIANCE)))
            .path("items");

    assertThat(ids(items)).containsExactly(second.caseId(), third.caseId(), first.caseId());
    assertThat(items.size()).isEqualTo(3);
  }

  @Test
  void filtersByRuleAndAccount() throws Exception {
    UUID accountId = newAccount();
    UUID otherAccount = newAccount();
    Fixture selected = addAlert(accountId, Rule.R2, BASE);
    addAlert(accountId, Rule.R1, BASE.plusSeconds(172800));
    addAlert(otherAccount, Rule.R2, BASE);

    JsonNode body = json(get("/v1/cases?rule=R2&account_id=" + accountId, token(Role.COMPLIANCE)));

    assertThat(ids(body.path("items"))).containsExactly(selected.caseId());
    assertThat(body.path("items").get(0).path("rule").asString()).isEqualTo("R2");
    assertThat(body.path("items").get(0).path("account_id").asString())
        .isEqualTo(accountId.toString());
  }

  @Test
  void pagingReturnsEveryCaseOnce() throws Exception {
    UUID accountId = newAccount();
    List<UUID> expected = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      expected.add(addAlert(accountId, Rule.R3, BASE.plusSeconds(index * 172800L)).caseId());
    }

    List<UUID> received = new ArrayList<>();
    List<Integer> pageSizes = new ArrayList<>();
    String cursor = null;
    do {
      String path = "/v1/cases?account_id=" + accountId + "&limit=2";
      if (cursor != null) {
        path += "&cursor=" + cursor;
      }
      JsonNode body = json(get(path, token(Role.COMPLIANCE)));
      JsonNode items = body.path("items");
      pageSizes.add(items.size());
      received.addAll(ids(items));
      cursor = body.path("next_cursor").isNull() ? null : body.path("next_cursor").asString();
    } while (cursor != null);

    assertThat(pageSizes).containsExactly(2, 2, 1);
    assertThat(received).containsExactlyElementsOf(expected);
    assertThat(received).doesNotHaveDuplicates();
  }

  @Test
  void detailIncludesAlertsAndEvents() throws Exception {
    UUID accountId = newAccount();
    Fixture first = addAlert(accountId, Rule.R1, BASE);
    Fixture second = addAlert(accountId, Rule.R1, BASE.plusSeconds(30));

    JsonNode body = json(get("/v1/cases/" + first.caseId(), token(Role.COMPLIANCE)));

    assertThat(body.path("alert_count").asInt()).isEqualTo(2);
    JsonNode alerts = body.path("alerts");
    assertThat(alerts.size()).isEqualTo(2);
    assertThat(alerts.get(0).path("id").asLong()).isEqualTo(first.alertId());
    assertThat(alerts.get(1).path("id").asLong()).isEqualTo(second.alertId());
    JsonNode events = body.path("events");
    assertThat(events.size()).isEqualTo(2);
    assertThat(events.get(0).path("type").asString()).isEqualTo("OPENED");
    assertThat(events.get(0).path("actor").asString()).isEqualTo("monitor");
    assertThat(events.get(1).path("type").asString()).isEqualTo("ALERT_ADDED");
  }

  @Test
  void assignMovesToInReview() throws Exception {
    Fixture fixture = addAlert(newAccount(), Rule.R1, BASE);
    String clientId = "case-reviewer";
    String compliance = ISSUER.token(clientId, Role.COMPLIANCE);

    HttpResponse<String> assigned =
        post(
            "/v1/cases/" + fixture.caseId() + "/assign",
            compliance,
            "{\"assignee\":\"analyst-7\"}");
    JsonNode firstBody = json(assigned);
    assertThat(assigned.statusCode()).isEqualTo(200);
    assertThat(firstBody.path("status").asString()).isEqualTo("IN_REVIEW");
    assertThat(firstBody.path("assignee").asString()).isEqualTo("analyst-7");
    assertEvent(firstBody.path("events").get(1), "ASSIGNED", "analyst-7", clientId);

    JsonNode reassigned =
        json(
            post(
                "/v1/cases/" + fixture.caseId() + "/assign",
                compliance,
                "{\"assignee\":\"analyst-8\"}"));
    assertThat(reassigned.path("status").asString()).isEqualTo("IN_REVIEW");
    assertThat(reassigned.path("assignee").asString()).isEqualTo("analyst-8");
    assertThat(reassigned.path("events").size()).isEqualTo(3);
    assertEvent(reassigned.path("events").get(2), "ASSIGNED", "analyst-8", clientId);
  }

  @Test
  void noteIsAppended() throws Exception {
    Fixture fixture = addAlert(newAccount(), Rule.R1, BASE);

    HttpResponse<String> response =
        post(
            "/v1/cases/" + fixture.caseId() + "/notes",
            token(Role.COMPLIANCE),
            "{\"text\":\"customer contacted\"}");
    JsonNode body = json(response);

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(body.path("status").asString()).isEqualTo("OPEN");
    assertThat(body.path("events").size()).isEqualTo(2);
    assertEvent(body.path("events").get(1), "NOTE", "customer contacted", "test-compliance");
  }

  @Test
  void closeWithResolution() throws Exception {
    Fixture escalated = addAlert(newAccount(), Rule.R1, BASE);
    HttpResponse<String> escalatedResponse =
        post(
            "/v1/cases/" + escalated.caseId() + "/close",
            token(Role.COMPLIANCE),
            "{\"resolution\":\"ESCALATED\",\"note\":\"sent to desk\"}");
    JsonNode escalatedBody = json(escalatedResponse);

    assertThat(escalatedResponse.statusCode()).isEqualTo(200);
    assertThat(escalatedBody.path("status").asString()).isEqualTo("CLOSED_ESCALATED");
    assertThat(escalatedBody.path("closed_at").isNull()).isFalse();
    assertEvent(
        escalatedBody.path("events").get(1),
        "CLOSED",
        "ESCALATED: sent to desk",
        "test-compliance");

    Fixture noAction = addAlert(newAccount(), Rule.R1, BASE);
    JsonNode noActionBody =
        json(
            post(
                "/v1/cases/" + noAction.caseId() + "/close",
                token(Role.COMPLIANCE),
                "{\"resolution\":\"NO_ACTION\"}"));
    assertThat(noActionBody.path("status").asString()).isEqualTo("CLOSED_NO_ACTION");
    assertEvent(noActionBody.path("events").get(1), "CLOSED", "NO_ACTION", "test-compliance");
  }

  @Test
  void closedCaseRejectsChanges() throws Exception {
    Fixture fixture = addAlert(newAccount(), Rule.R1, BASE);
    String path = "/v1/cases/" + fixture.caseId();
    String compliance = token(Role.COMPLIANCE);
    assertThat(post(path + "/close", compliance, "{\"resolution\":\"NO_ACTION\"}").statusCode())
        .isEqualTo(200);

    assertCode(
        post(path + "/assign", compliance, "{\"assignee\":\"analyst\"}"), 422, "CASE_CLOSED");
    assertCode(post(path + "/notes", compliance, "{\"text\":\"follow up\"}"), 422, "CASE_CLOSED");
    assertCode(
        post(path + "/close", compliance, "{\"resolution\":\"ESCALATED\"}"), 422, "CASE_CLOSED");
  }

  @Test
  void newAlertAfterCloseOpensNewCase() throws Exception {
    UUID accountId = newAccount();
    Fixture first = addAlert(accountId, Rule.R1, BASE);
    assertThat(
            post(
                    "/v1/cases/" + first.caseId() + "/close",
                    token(Role.COMPLIANCE),
                    "{\"resolution\":\"NO_ACTION\"}")
                .statusCode())
        .isEqualTo(200);

    Fixture second = addAlert(accountId, Rule.R1, BASE.plusSeconds(60));

    assertThat(second.caseId()).isNotEqualTo(first.caseId());
    JsonNode items =
        json(get("/v1/cases?account_id=" + accountId, token(Role.COMPLIANCE))).path("items");
    assertThat(ids(items)).containsExactly(first.caseId(), second.caseId());
  }

  @Test
  void validationErrors() throws Exception {
    String compliance = token(Role.COMPLIANCE);
    Fixture fixture = addAlert(newAccount(), Rule.R1, BASE);
    String path = "/v1/cases/" + fixture.caseId();

    assertCode(get("/v1/cases?status=DONE", compliance), 400, "VALIDATION_FAILED");
    assertCode(get("/v1/cases?rule=R9", compliance), 400, "VALIDATION_FAILED");
    assertCode(post(path + "/assign", compliance, "{\"assignee\":\"\"}"), 400, "VALIDATION_FAILED");
    assertCode(
        post(path + "/notes", compliance, "{\"text\":\"" + "x".repeat(2001) + "\"}"),
        400,
        "VALIDATION_FAILED");
    assertCode(
        post(path + "/close", compliance, "{\"resolution\":\"MAYBE\"}"), 400, "VALIDATION_FAILED");
    assertCode(get("/v1/cases?cursor=***", compliance), 400, "VALIDATION_FAILED");
    assertCode(get("/v1/cases/" + UUID.randomUUID(), compliance), 404, "NOT_FOUND");
    assertCode(get("/v1/cases/not-a-uuid", compliance), 400, "VALIDATION_FAILED");
  }

  @Test
  void scopes() throws Exception {
    Fixture fixture = addAlert(newAccount(), Rule.R1, BASE);
    String path = "/v1/cases/" + fixture.caseId();
    String auditor = token(Role.AUDITOR);
    assertThat(get("/v1/cases?account_id=" + UuidV7.next(clock()), auditor).statusCode())
        .isEqualTo(200);
    assertThat(get(path, auditor).statusCode()).isEqualTo(200);
    assertThat(post(path + "/assign", auditor, "{\"assignee\":\"analyst\"}").statusCode())
        .isEqualTo(403);
    assertThat(post(path + "/notes", auditor, "{\"text\":\"note\"}").statusCode()).isEqualTo(403);
    assertThat(post(path + "/close", auditor, "{\"resolution\":\"NO_ACTION\"}").statusCode())
        .isEqualTo(403);

    String operator = token(Role.OPERATOR);
    assertThat(get("/v1/cases", operator).statusCode()).isEqualTo(403);
    assertThat(get(path, operator).statusCode()).isEqualTo(403);
    assertThat(post(path + "/assign", operator, "{\"assignee\":\"analyst\"}").statusCode())
        .isEqualTo(403);
    assertThat(post(path + "/notes", operator, "{\"text\":\"note\"}").statusCode()).isEqualTo(403);
    assertThat(post(path + "/close", operator, "{\"resolution\":\"NO_ACTION\"}").statusCode())
        .isEqualTo(403);

    assertThat(get("/v1/cases", null).statusCode()).isEqualTo(401);
    assertThat(get(path, null).statusCode()).isEqualTo(401);
    assertThat(post(path + "/assign", null, "{\"assignee\":\"analyst\"}").statusCode())
        .isEqualTo(401);
    assertThat(post(path + "/notes", null, "{\"text\":\"note\"}").statusCode()).isEqualTo(401);
    assertThat(post(path + "/close", null, "{\"resolution\":\"NO_ACTION\"}").statusCode())
        .isEqualTo(401);
  }

  @Test
  void concurrentCloseAndAssignLeaveOneOutcome() throws Exception {
    for (int attempt = 0; attempt < 3; attempt++) {
      Fixture fixture = addAlert(newAccount(), Rule.R1, BASE.plusSeconds(attempt * 172800L));
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      int assignStatus;
      try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
        Future<HttpResponse<String>> close =
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return post(
                      "/v1/cases/" + fixture.caseId() + "/close",
                      token(Role.COMPLIANCE),
                      "{\"resolution\":\"NO_ACTION\"}");
                });
        Future<HttpResponse<String>> assign =
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return post(
                      "/v1/cases/" + fixture.caseId() + "/assign",
                      token(Role.COMPLIANCE),
                      "{\"assignee\":\"analyst\"}");
                });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        HttpResponse<String> closeResponse = close.get(20, TimeUnit.SECONDS);
        HttpResponse<String> assignResponse = assign.get(20, TimeUnit.SECONDS);
        assertThat(closeResponse.statusCode()).isEqualTo(200);
        assertThat(assignResponse.statusCode()).isIn(200, 422);
        assignStatus = assignResponse.statusCode();
        if (assignResponse.statusCode() == 422) {
          assertThat(json(assignResponse).path("error").path("code").asString())
              .isEqualTo("CASE_CLOSED");
        }
      }

      JsonNode detail = json(get("/v1/cases/" + fixture.caseId(), token(Role.COMPLIANCE)));
      assertThat(detail.path("status").asString()).isEqualTo("CLOSED_NO_ACTION");
      long closedEvents = 0;
      for (JsonNode event : detail.path("events")) {
        if ("CLOSED".equals(event.path("type").asString())) {
          closedEvents++;
        }
      }
      assertThat(closedEvents).isEqualTo(1);
      if (assignStatus == 200) {
        List<String> eventTypes = new ArrayList<>();
        for (JsonNode event : detail.path("events")) {
          eventTypes.add(event.path("type").asString());
        }
        assertThat(eventTypes).containsSubsequence("ASSIGNED", "CLOSED");
      }
    }
  }

  private Fixture addAlert(UUID accountId, Rule rule, Instant alertTime) throws SQLException {
    RuleEngine.AlertCandidate candidate =
        new RuleEngine.AlertCandidate(
            rule, accountId, UUID.randomUUID().toString(), alertTime, "test alert");
    try (Connection connection = appJdbc()) {
      connection.setAutoCommit(false);
      long alertId =
          app()
              .getBean(AlertStore.class)
              .insert(connection, candidate, clock().instant())
              .orElseThrow();
      app().getBean(CaseAssigner.class).assign(connection, alertId, candidate);
      UUID caseId;
      try (PreparedStatement select =
          connection.prepareStatement("SELECT case_id FROM monitor.alert WHERE id = ?")) {
        select.setLong(1, alertId);
        try (ResultSet row = select.executeQuery()) {
          row.next();
          caseId = row.getObject("case_id", UUID.class);
        }
      }
      connection.commit();
      return new Fixture(caseId, alertId);
    }
  }

  private UUID newAccount() {
    return UuidV7.next(clock());
  }

  private Clock clock() {
    return app().getBean(Clock.class);
  }

  private static List<UUID> ids(JsonNode items) {
    List<UUID> result = new ArrayList<>();
    for (JsonNode item : items) {
      result.add(UUID.fromString(item.path("id").asString()));
    }
    return result;
  }

  private static JsonNode json(HttpResponse<String> response) throws IOException {
    return JSON.readTree(response.body());
  }

  private static void assertCode(HttpResponse<String> response, int status, String code)
      throws IOException {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(json(response).path("error").path("code").asString()).isEqualTo(code);
  }

  private static void assertEvent(JsonNode event, String type, String detail, String actor) {
    assertThat(event.path("type").asString()).isEqualTo(type);
    assertThat(event.path("detail").asString()).isEqualTo(detail);
    assertThat(event.path("actor").asString()).isEqualTo(actor);
  }

  private record Fixture(UUID caseId, long alertId) {}
}
