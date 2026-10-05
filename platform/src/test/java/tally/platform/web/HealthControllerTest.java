package tally.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import tally.platform.TestApp;

@SpringBootTest(classes = TestApp.class)
@AutoConfigureMockMvc
@Import(HealthControllerTest.Checks.class)
class HealthControllerTest {

  @TestConfiguration
  static class Checks {

    @Bean
    ReadinessCheck upCheck() {
      return check("cache", true);
    }

    @Bean
    ReadinessCheck downCheck() {
      return check("queue", false);
    }

    private static ReadinessCheck check(String name, boolean up) {
      return new ReadinessCheck() {
        @Override
        public String name() {
          return name;
        }

        @Override
        public boolean up() {
          return up;
        }
      };
    }
  }

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    TestApp.authProperties(registry);
  }

  @Test
  void healthIsOpen() throws Exception {
    mvc.perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
  }

  @Test
  void readyReportsDownCheck() throws Exception {
    mvc.perform(get("/ready"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"))
        .andExpect(jsonPath("$.checks.cache").value("UP"))
        .andExpect(jsonPath("$.checks.queue").value("DOWN"));
  }

  @Test
  void databaseCheckIsDownWhenNoConnectionCanBeOpened() {
    DbReadinessCheck check =
        new DbReadinessCheck(
            new AbstractDataSource() {
              @Override
              public Connection getConnection() throws SQLException {
                throw new SQLException("connection refused", "08001");
              }

              @Override
              public Connection getConnection(String username, String password)
                  throws SQLException {
                return getConnection();
              }
            });

    assertThat(check.name()).isEqualTo("db");
    assertThat(check.up()).isFalse();
  }
}
