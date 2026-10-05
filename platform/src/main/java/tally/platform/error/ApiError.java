package tally.platform.error;

import java.util.List;

/** The body of every error response. */
public record ApiError(Body error) {

  public record Body(String code, String message, String requestId, List<Detail> details) {}

  public record Detail(String field, String issue) {}
}
