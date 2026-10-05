package tally.ledger;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import tally.platform.web.DbReadinessCheck;

@SpringBootApplication(scanBasePackages = {"tally.ledger", "tally.platform"})
@ConfigurationPropertiesScan
public class LedgerApplication {

  /** Reads ledger.yaml, so that several services can run in one JVM without sharing settings. */
  public static SpringApplicationBuilder builder() {
    return new SpringApplicationBuilder(LedgerApplication.class)
        .properties("spring.config.name=ledger");
  }

  public static void main(String[] args) {
    builder().run(args);
  }

  @Bean
  DbReadinessCheck dbReadinessCheck(DataSource dataSource) {
    return new DbReadinessCheck(dataSource);
  }
}
