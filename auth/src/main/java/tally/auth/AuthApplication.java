package tally.auth;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import tally.platform.web.DbReadinessCheck;

@SpringBootApplication(scanBasePackages = {"tally.auth", "tally.platform"})
@ConfigurationPropertiesScan
public class AuthApplication {

  @Bean
  DbReadinessCheck dbReadinessCheck(DataSource dataSource) {
    return new DbReadinessCheck(dataSource);
  }

  public static SpringApplicationBuilder builder() {
    return new SpringApplicationBuilder(AuthApplication.class)
        .properties("spring.config.name=auth");
  }

  public static void main(String[] args) {
    builder().run(args);
  }
}
