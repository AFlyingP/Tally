package tally.monitor.cases;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import tally.monitor.api.CaseDto;
import tally.monitor.cases.CaseStore.AlertRow;
import tally.monitor.cases.CaseStore.CaseRow;
import tally.monitor.cases.CaseStore.EventRow;
import tally.platform.error.ApiError;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.security.Caller;

@Service
public class CaseService {

  private static final Set<String> STATUSES =
      Set.of("OPEN", "IN_REVIEW", "CLOSED_NO_ACTION", "CLOSED_ESCALATED");
  private static final Set<String> RULES = Set.of("R1", "R2", "R3");

  private final DataSource dataSource;
  private final CaseStore cases;
  private final Clock clock;

  public CaseService(DataSource dataSource, CaseStore cases, Clock clock) {
    this.dataSource = dataSource;
    this.cases = cases;
    this.clock = clock;
  }

  public CaseDto.CasePage list(String status, String rule, UUID accountId, int limit, String cursor)
      throws SQLException {
    if (status != null && !STATUSES.contains(status)) {
      throw invalid("status", "must be a known case status");
    }
    if (rule != null && !RULES.contains(rule)) {
      throw invalid("rule", "must be a known rule");
    }
    if (limit < 1 || limit > 200) {
      throw invalid("limit", "must be between 1 and 200");
    }
    CursorPosition position = cursor == null ? null : decodeCursor(cursor);
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      List<CaseRow> page =
          cases.list(
              connection,
              status,
              rule,
              accountId,
              position == null ? null : position.openedAt(),
              position == null ? null : position.id(),
              limit + 1);
      String nextCursor = null;
      if (page.size() > limit) {
        page = page.subList(0, limit);
        CaseRow last = page.getLast();
        nextCursor = encodeCursor(last.openedAt() + "|" + last.id());
      }
      return new CaseDto.CasePage(page.stream().map(CaseService::toCase).toList(), nextCursor);
    }
  }

  public CaseDto.CaseDetail get(UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      return detail(connection, id);
    }
  }

  public CaseDto.CaseDetail assign(Caller caller, UUID id, CaseDto.Assign request, String requestId)
      throws SQLException {
    return transaction(
        connection -> {
          CaseRow row = writableCase(connection, id);
          Instant now = now();
          cases.update(connection, id, "IN_REVIEW", request.assignee(), row.closedAt(), now);
          cases.event(
              connection, id, "ASSIGNED", caller.clientId(), request.assignee(), requestId, now);
          return detail(connection, id);
        });
  }

  public CaseDto.CaseDetail note(Caller caller, UUID id, CaseDto.Note request, String requestId)
      throws SQLException {
    return transaction(
        connection -> {
          CaseRow row = writableCase(connection, id);
          Instant now = now();
          cases.update(connection, id, row.status(), row.assignee(), row.closedAt(), now);
          cases.event(connection, id, "NOTE", caller.clientId(), request.text(), requestId, now);
          return detail(connection, id);
        });
  }

  public CaseDto.CaseDetail close(Caller caller, UUID id, CaseDto.Close request, String requestId)
      throws SQLException {
    if (!Set.of("NO_ACTION", "ESCALATED").contains(request.resolution())) {
      throw invalid("resolution", "must be NO_ACTION or ESCALATED");
    }
    return transaction(
        connection -> {
          CaseRow row = writableCase(connection, id);
          Instant now = now();
          String status =
              "NO_ACTION".equals(request.resolution()) ? "CLOSED_NO_ACTION" : "CLOSED_ESCALATED";
          String detail =
              request.note() == null
                  ? request.resolution()
                  : request.resolution() + ": " + request.note();
          cases.update(connection, id, status, row.assignee(), now, now);
          cases.event(connection, id, "CLOSED", caller.clientId(), detail, requestId, now);
          return detail(connection, id);
        });
  }

  private <T> T transaction(SqlWork<T> work) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private CaseRow writableCase(Connection connection, UUID id) throws SQLException {
    CaseRow row =
        cases
            .lock(connection, id)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "case not found"));
    if (row.status().startsWith("CLOSED_")) {
      throw new ApiException(ErrorCode.CASE_CLOSED, "case is closed");
    }
    return row;
  }

  private CaseDto.CaseDetail detail(Connection connection, UUID id) throws SQLException {
    CaseRow row =
        cases
            .find(connection, id)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "case not found"));
    List<CaseDto.Alert> alerts =
        cases.alerts(connection, id).stream().map(CaseService::toAlert).toList();
    List<CaseDto.Event> events =
        cases.events(connection, id).stream().map(CaseService::toEvent).toList();
    return new CaseDto.CaseDetail(
        row.id(),
        row.accountId(),
        row.rule(),
        row.status(),
        row.assignee(),
        row.alertCount(),
        row.openedAt(),
        row.updatedAt(),
        row.closedAt(),
        alerts,
        events);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static CaseDto.Case toCase(CaseRow row) {
    return new CaseDto.Case(
        row.id(),
        row.accountId(),
        row.rule(),
        row.status(),
        row.assignee(),
        row.alertCount(),
        row.openedAt(),
        row.updatedAt(),
        row.closedAt());
  }

  private static CaseDto.Alert toAlert(AlertRow row) {
    return new CaseDto.Alert(row.id(), row.triggerKey(), row.alertTime(), row.detail());
  }

  private static CaseDto.Event toEvent(EventRow row) {
    return new CaseDto.Event(row.type(), row.actor(), row.detail(), row.occurredAt());
  }

  private static String encodeCursor(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static CursorPosition decodeCursor(String cursor) {
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(cursor);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(cursor)) {
        throw invalid("cursor", "must be a valid cursor");
      }
      String value =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      String[] parts = value.split("\\|", -1);
      if (parts.length != 2) {
        throw invalid("cursor", "must be a valid cursor");
      }
      Instant openedAt = Instant.parse(parts[0]);
      UUID id = UUID.fromString(parts[1]);
      if (!id.toString().equals(parts[1])) {
        throw invalid("cursor", "must be a valid cursor");
      }
      return new CursorPosition(openedAt, id);
    } catch (IllegalArgumentException | DateTimeException | CharacterCodingException exception) {
      throw invalid("cursor", "must be a valid cursor");
    }
  }

  private static ApiException invalid(String field, String issue) {
    return new ApiException(
        ErrorCode.VALIDATION_FAILED,
        "request is not valid",
        List.of(new ApiError.Detail(field, issue)));
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }

  private record CursorPosition(Instant openedAt, UUID id) {}
}
