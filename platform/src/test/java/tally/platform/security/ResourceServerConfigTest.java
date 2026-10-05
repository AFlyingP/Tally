package tally.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tally.platform.TestApp;
import tally.platform.error.ErrorCode;
import tally.platform.testing.TestIssuer;

@SpringBootTest(classes = TestApp.class)
@AutoConfigureMockMvc
@Import(ResourceServerConfigTest.DeniedLog.class)
class ResourceServerConfigTest {

  @TestConfiguration
  static class DeniedLog {

    final List<RequestDenied> events = new CopyOnWriteArrayList<>();

    @EventListener
    void on(RequestDenied event) {
      events.add(event);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired DeniedLog denied;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    TestApp.authProperties(registry);
  }

  @BeforeEach
  void clearEvents() {
    denied.events.clear();
  }

  @Test
  void rejectsMissingToken() throws Exception {
    mvc.perform(get("/t/scoped"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.error.details").isEmpty());

    assertThat(denied.events)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.clientId()).isEqualTo("anonymous");
              assertThat(event.code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
            });
  }

  @Test
  void rejectsExpiredToken() throws Exception {
    mvc.perform(get("/t/scoped").header(HttpHeaders.AUTHORIZATION, bearer(expired())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
  }

  @Test
  void rejectsTokenFromAnotherKey() throws Exception {
    try (TestIssuer other = TestIssuer.start()) {
      mvc.perform(
              get("/t/scoped")
                  .header(HttpHeaders.AUTHORIZATION, bearer(other.token(Role.OPERATOR))))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
  }

  @Test
  void rejectsMissingScope() throws Exception {
    String token = TestApp.ISSUER.tokenWithScopes("c1", Scopes.TRANSACTIONS_READ);

    mvc.perform(get("/t/scoped").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }

  @Test
  void acceptsRoleWithScope() throws Exception {
    String token = TestApp.ISSUER.token(Role.SERVICE_DESK);

    mvc.perform(get("/t/scoped").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());
    mvc.perform(get("/t/open-data").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());
  }

  @Test
  void publishesDeniedEvent() throws Exception {
    String token = TestApp.ISSUER.tokenWithScopes("c1", Scopes.TRANSACTIONS_READ);

    mvc.perform(get("/t/scoped").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isForbidden());

    assertThat(denied.events)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.code()).isEqualTo(ErrorCode.FORBIDDEN);
              assertThat(event.clientId()).isEqualTo("c1");
              assertThat(event.scopes()).isEqualTo(Scopes.TRANSACTIONS_READ);
              assertThat(event.method()).isEqualTo("GET");
              assertThat(event.path()).isEqualTo("/t/scoped");
            });
  }

  private static String expired() {
    return TestApp.ISSUER.expiredToken(Role.OPERATOR);
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }
}
