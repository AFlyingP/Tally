package tally.platform.error;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tally.platform.web.RequestIdFilter;
import tools.jackson.databind.json.JsonMapper;

/** Renders every failure of a request in the one error shape. */
@RestControllerAdvice
public class ApiErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

  // SQLSTATEs raised by the constraints and triggers that guard the ledger invariants
  private static final Set<String> INVARIANT_STATES =
      Set.of("TL001", "TL002", "TL003", "23514", "23505");

  private final JsonMapper mapper;

  public ApiErrorHandler(JsonMapper mapper) {
    this.mapper = mapper;
  }

  /** Writes an error response from a servlet filter, where no exception handler runs. */
  public void write(HttpServletResponse response, ErrorCode code, String message)
      throws IOException {
    response.setStatus(code.status());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    mapper.writeValue(response.getOutputStream(), body(code, message, List.of()));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> api(ApiException e) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(e.code().status());
    if (e.retryAfterSeconds() != null) {
      response.header(HttpHeaders.RETRY_AFTER, e.retryAfterSeconds().toString());
    }
    return response.body(body(e.code(), e.getMessage(), e.details()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
    return respond(ErrorCode.MALFORMED_REQUEST, "request body is not valid", List.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
    List<ApiError.Detail> details =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> new ApiError.Detail(snake(f.getField()), f.getDefaultMessage()))
            .toList();
    return invalid(details);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ApiError> invalidParameter(ConstraintViolationException e) {
    List<ApiError.Detail> details =
        e.getConstraintViolations().stream()
            .map(
                v -> {
                  String path = v.getPropertyPath().toString();
                  String name = path.substring(path.lastIndexOf('.') + 1);
                  return new ApiError.Detail(snake(name), v.getMessage());
                })
            .toList();
    return invalid(details);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiError> wrongType(MethodArgumentTypeMismatchException e) {
    return invalid(List.of(new ApiError.Detail(snake(e.getName()), "has the wrong format")));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e) {
    return invalid(List.of(new ApiError.Detail(e.getParameterName(), "is required")));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException e) {
    return invalid(List.of(new ApiError.Detail(e.getHeaderName(), "is required")));
  }

  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  ResponseEntity<ApiError> notFound(Exception e) {
    return respond(ErrorCode.NOT_FOUND, "path does not exist", List.of());
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> wrongMethod(HttpRequestMethodNotSupportedException e) {
    return respond(ErrorCode.METHOD_NOT_ALLOWED, "method is not supported here", List.of());
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiError> wrongMediaType(HttpMediaTypeNotSupportedException e) {
    return respond(
        ErrorCode.UNSUPPORTED_MEDIA_TYPE, "content type must be application/json", List.of());
  }

  @ExceptionHandler(SQLException.class)
  ResponseEntity<ApiError> sql(SQLException e) {
    if (INVARIANT_STATES.contains(e.getSQLState())) {
      log.error("database rejected a write sqlstate={}", e.getSQLState(), e);
      return respond(ErrorCode.INTERNAL_ERROR, "internal error", List.of());
    }
    log.warn("database unavailable sqlstate={}: {}", e.getSQLState(), e.getMessage());
    return ResponseEntity.status(ErrorCode.SERVICE_UNAVAILABLE.status())
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(body(ErrorCode.SERVICE_UNAVAILABLE, "database is unavailable", List.of()));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiError> dataAccess(DataAccessException e) {
    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql) {
        return sql(sql);
      }
    }
    return unexpected(e);
  }

  // Thrown again so that the security filter chain writes the 403 and reports the denial.
  @ExceptionHandler(AccessDeniedException.class)
  void denied(AccessDeniedException e) {
    throw e;
  }

  @ExceptionHandler(Throwable.class)
  ResponseEntity<ApiError> unexpected(Throwable e) {
    log.error("unexpected failure", e);
    return respond(ErrorCode.INTERNAL_ERROR, "internal error", List.of());
  }

  private static ResponseEntity<ApiError> invalid(List<ApiError.Detail> details) {
    return respond(ErrorCode.VALIDATION_FAILED, "request is not valid", details);
  }

  private static ResponseEntity<ApiError> respond(
      ErrorCode code, String message, List<ApiError.Detail> details) {
    return ResponseEntity.status(code.status()).body(body(code, message, details));
  }

  private static ApiError body(ErrorCode code, String message, List<ApiError.Detail> details) {
    return new ApiError(
        new ApiError.Body(code.name(), message, RequestIdFilter.current(), details));
  }

  private static String snake(String name) {
    return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }
}
