package tally.platform.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tally.platform.TestApp;

@SpringBootTest(classes = TestApp.class)
@AutoConfigureMockMvc
class RequestIdFilterTest {

  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    TestApp.authProperties(registry);
  }

  @Test
  void echoesValidRequestId() throws Exception {
    mvc.perform(get("/health").header("X-Request-Id", "abcdef12-3456"))
        .andExpect(header().string("X-Request-Id", "abcdef12-3456"));
  }

  @Test
  void replacesInvalidRequestId() throws Exception {
    mvc.perform(get("/health").header("X-Request-Id", "x"))
        .andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
  }

  @Test
  void createsRequestIdWhenHeaderIsMissing() throws Exception {
    mvc.perform(get("/health"))
        .andExpect(header().string("X-Request-Id", matchesPattern(UUID_PATTERN)));
  }
}
