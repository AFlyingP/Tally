package tally.platform.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tally.platform.TestApp;
import tally.platform.security.Role;

@SpringBootTest(classes = TestApp.class)
@AutoConfigureMockMvc
class ApiErrorHandlerTest {

  @Autowired MockMvc mvc;
  @Autowired ApiErrorHandler handler;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    TestApp.authProperties(registry);
  }

  @Test
  void rendersEveryErrorCodeWithItsStatus() throws Exception {
    for (ErrorCode code : ErrorCode.values()) {
      mvc.perform(get("/t/fail/" + code.name()).header(HttpHeaders.AUTHORIZATION, bearer()))
          .andExpect(status().is(code.status()))
          .andExpect(jsonPath("$.error.code").value(code.name()))
          .andExpect(jsonPath("$.error.request_id").isNotEmpty())
          .andExpect(jsonPath("$.error.details").isArray())
          .andExpect(jsonPath("$.error.details").isEmpty());
    }
  }

  @Test
  void rejectsUnknownPropertyAsMalformed() throws Exception {
    echo("{\"amount\":1,\"at\":\"2026-01-05T10:00:00Z\",\"x\":1}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
  }

  @Test
  void rejectsFractionalAmountAsMalformed() throws Exception {
    echo("{\"amount\":1.5,\"at\":\"2026-01-05T10:00:00Z\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    echo("{\"amount\":\"1\",\"at\":\"2026-01-05T10:00:00Z\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
  }

  @Test
  void rejectsTimestampWithoutZone() throws Exception {
    echo("{\"amount\":1,\"at\":\"2026-01-05T10:00:00\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    echo("{\"amount\":1,\"at\":1767607200}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
  }

  @Test
  void writesTimestampsWithSixFractionDigits() throws Exception {
    echo("{\"amount\":1,\"at\":\"2026-01-05T10:00:00Z\"}")
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    "{\"amount\":1,\"at\":\"2026-01-05T10:00:00.000000Z\"}",
                    JsonCompareMode.STRICT));
  }

  @Test
  void mapsUnexpectedExceptionToInternalError() throws Exception {
    mvc.perform(get("/t/boom").header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
  }

  @Test
  void mapsUnknownPathToNotFound() throws Exception {
    mvc.perform(get("/nope").header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
  }

  @Test
  void mapsWrongMethodAndMediaType() throws Exception {
    mvc.perform(post("/t/boom").header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    mvc.perform(
            post("/t/echo")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.TEXT_PLAIN)
                .content("1"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void namesTheParameterWithTheWrongFormat() throws Exception {
    mvc.perform(get("/t/fail/NO_SUCH_CODE").header(HttpHeaders.AUTHORIZATION, bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.details[0].field").value("code"));
  }

  @Test
  void mapsInvariantViolationToInternalError() {
    ResponseEntity<ApiError> response = handler.sql(new SQLException("check failed", "23514"));

    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
  }

  @Test
  void mapsBrokenConnectionToServiceUnavailable() {
    ResponseEntity<ApiError> response =
        handler.dataAccess(
            new DataAccessResourceFailureException(
                "no connection", new SQLException("connection refused", "08001")));

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    assertThat(response.getBody().error().code()).isEqualTo("SERVICE_UNAVAILABLE");
  }

  @Test
  void addsRetryAfterWhenTheExceptionCarriesIt() {
    ResponseEntity<ApiError> response =
        handler.api(new ApiException(ErrorCode.REQUEST_IN_FLIGHT, "still running").retryAfter(1));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }

  private ResultActions echo(String body) throws Exception {
    return mvc.perform(
            post("/t/echo")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(header().exists("X-Request-Id"));
  }

  private static String bearer() {
    return "Bearer " + TestApp.ISSUER.token(Role.OPERATOR);
  }
}
