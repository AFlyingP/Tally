package tally.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.FilterChain;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tally.platform.TestApp;
import tally.platform.error.ApiErrorHandler;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.security.Role;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = TestApp.class)
@AutoConfigureMockMvc
class BodyLimitFilterTest {

  @Autowired MockMvc mvc;
  @Autowired ApiErrorHandler errors;
  @Autowired JsonMapper json;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    TestApp.authProperties(registry);
  }

  @Test
  void rejectsBodyOverLimit() throws Exception {
    mvc.perform(
            post("/t/echo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestApp.ISSUER.token(Role.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[1_048_577]))
        .andExpect(status().isContentTooLarge())
        .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
  }

  @Test
  void rejectsStreamedBodyOverLimitWhileReading() throws Exception {
    BodyLimitFilter filter = new BodyLimitFilter(errors, 1024, Set.of("/big"), 4096);
    FilterChain readsBody = (request, response) -> request.getInputStream().readAllBytes();

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    streamed("/t/echo", 1025), new MockHttpServletResponse(), readsBody))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));

    MockHttpServletResponse accepted = new MockHttpServletResponse();
    filter.doFilter(streamed("/big", 1025), accepted, readsBody);
    assertThat(accepted.getStatus()).isEqualTo(200);
  }

  @Test
  void limitSurvivesJsonParsingOfAStreamedBody() throws Exception {
    BodyLimitFilter filter = new BodyLimitFilter(errors, 1024, Set.of(), 4096);
    FilterChain parsesBody =
        (request, response) -> json.readValue(request.getInputStream(), TestApp.Echo.class);
    MockHttpServletRequest request = streamed("/t/echo", 0);
    request.setContent(
        ("{\"amount\":1,\"at\":\"" + "x".repeat(2000) + "\"}").getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), parsesBody))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
  }

  @Test
  void limitAppliesToTheReaderOfAStreamedBody() {
    BodyLimitFilter filter = new BodyLimitFilter(errors, 1024, Set.of(), 4096);
    FilterChain readsText =
        (request, response) -> request.getReader().transferTo(new StringWriter());

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    streamed("/t/echo", 1025), new MockHttpServletResponse(), readsText))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
  }

  @Test
  void refusesLimitOutsideTheAllowedRange() {
    assertThatThrownBy(() -> new BodyLimitFilter(errors, 10, Set.of(), 4096))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tally.web.max-body-bytes");
  }

  private static MockHttpServletRequest streamed(String path, int size) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", path) {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    request.setContent(new byte[size]);
    return request;
  }
}
