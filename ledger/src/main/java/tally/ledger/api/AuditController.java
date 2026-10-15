package tally.ledger.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tally.ledger.audit.AuditStore;
import tally.platform.error.ApiError;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;

@RestController
public class AuditController {

  private static final Set<String> RESOURCE_TYPES =
      Set.of(
          "account",
          "transaction",
          "hold",
          "schedule",
          "schedule_occurrence",
          "external_payment",
          "recon_run",
          "request");

  private final DataSource dataSource;
  private final AuditStore store;

  public AuditController(DataSource dataSource, AuditStore store) {
    this.dataSource = dataSource;
    this.store = store;
  }

  @GetMapping("/v1/audit")
  @PreAuthorize("hasAuthority('SCOPE_audit:read')")
  AuditDto.AuditPage query(
      @RequestParam(value = "resource_type", required = false) String resourceType,
      @RequestParam(value = "resource_id", required = false) String resourceId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String cursor)
      throws SQLException {
    if ((resourceType == null) != (resourceId == null)) {
      throw invalid("resource_type", "resource_type and resource_id must be given together");
    }
    if (resourceType != null && !RESOURCE_TYPES.contains(resourceType)) {
      throw invalid("resource_type", "must be a known resource type");
    }
    if (from != null && to != null && from.isAfter(to)) {
      throw invalid("from", "must not be after to");
    }
    if (limit < 1 || limit > 500) {
      throw invalid("limit", "must be between 1 and 500");
    }
    Instant afterTime = null;
    UUID afterId = null;
    if (cursor != null) {
      String[] parts = Cursor.decode(cursor).split("\\|", -1);
      if (parts.length != 2) {
        throw invalid("cursor", "must be a valid cursor");
      }
      try {
        afterTime = Instant.parse(parts[0]);
        afterId = UUID.fromString(parts[1]);
        if (!afterId.toString().equalsIgnoreCase(parts[1])) {
          throw invalid("cursor", "must be a valid cursor");
        }
      } catch (DateTimeException | IllegalArgumentException exception) {
        throw invalid("cursor", "must be a valid cursor");
      }
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      List<AuditDto.AuditRow> page =
          store.query(
              connection, resourceType, resourceId, from, to, afterTime, afterId, limit + 1);
      String nextCursor = null;
      if (page.size() > limit) {
        page = page.subList(0, limit);
        AuditDto.AuditRow last = page.getLast();
        nextCursor = Cursor.encode(last.occurredAt() + "|" + last.id());
      }
      return new AuditDto.AuditPage(page, nextCursor);
    }
  }

  private static ApiException invalid(String field, String issue) {
    return new ApiException(
        ErrorCode.VALIDATION_FAILED,
        "request is not valid",
        List.of(new ApiError.Detail(field, issue)));
  }
}
