package tally.monitor;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import tally.platform.web.DbReadinessCheck;

@SpringBootApplication(scanBasePackages = {"tally.monitor", "tally.platform"})
@ConfigurationPropertiesScan
public class MonitorApplication {

  public static SpringApplicationBuilder builder() {
    return new SpringApplicationBuilder(MonitorApplication.class)
        .properties("spring.config.name=monitor");
  }

  public static void main(String[] args) {
    builder().run(args);
  }

  @Bean
  DbReadinessCheck dbReadinessCheck(DataSource dataSource) {
    return new DbReadinessCheck(dataSource);
  }
}
