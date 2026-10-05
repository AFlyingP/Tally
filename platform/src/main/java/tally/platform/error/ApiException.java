package tally.platform.error;

import java.util.List;

/** A request failure that is rendered as an error response with the given code. */
public class ApiException extends RuntimeException {

  private final ErrorCode code;
  private final List<ApiError.Detail> details;
  private Integer retryAfterSeconds;

  public ApiException(ErrorCode code, String message) {
    this(code, message, List.of());
  }

  public ApiException(ErrorCode code, String message, List<ApiError.Detail> details) {
    super(message);
    this.code = code;
    this.details = List.copyOf(details);
  }

  /** Sets the value of the Retry-After response header. */
  public ApiException retryAfter(int seconds) {
    this.retryAfterSeconds = seconds;
    return this;
  }

  public ErrorCode code() {
    return code;
  }

  public List<ApiError.Detail> details() {
    return details;
  }

  public Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
