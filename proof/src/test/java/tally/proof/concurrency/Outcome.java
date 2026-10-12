package tally.proof.concurrency;

import tally.proof.harness.Http;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

public enum Outcome {
  POSTED,
  INSUFFICIENT,
  EXHAUSTED;

  public static Outcome of(Http.Response response) {
    if (response.status() == 201) {
      return POSTED;
    }
    if (response.status() == 422 || response.status() == 503) {
      String code;
      try {
        JsonNode body = response.json();
        code = body == null ? null : body.path("error").path("code").asString();
      } catch (JacksonException e) {
        throw unexpected(response);
      }
      if (response.status() == 422 && "INSUFFICIENT_FUNDS".equals(code)) {
        return INSUFFICIENT;
      }
      if (response.status() == 503 && "RETRY_EXHAUSTED".equals(code)) {
        return EXHAUSTED;
      }
    }
    throw unexpected(response);
  }

  private static AssertionError unexpected(Http.Response response) {
    return new AssertionError("unexpected response " + response.status() + " " + response.body());
  }
}
