package tally.ledger;

import java.io.PrintStream;
import java.util.Arrays;
import javax.sql.DataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import tally.ledger.audit.VerifyCommand;
import tally.ledger.check.CheckCommand;
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
    if (args.length > 0 && !args[0].startsWith("--")) {
      System.exit(command(args, System.out, System.err));
    } else {
      builder().run(args);
    }
  }

  static int command(String[] args, PrintStream out, PrintStream err) {
    if (args[0].equals("check-invariants") || args[0].equals("verify-audit")) {
      try (ConfigurableApplicationContext context =
          builder()
              .web(WebApplicationType.NONE)
              .properties("spring.flyway.enabled=false", "spring.main.banner-mode=off")
              .run("--logging.level.root=WARN", "--logging.level.tally=WARN")) {
        if (args[0].equals("verify-audit")) {
          return context
              .getBean(VerifyCommand.class)
              .run(Arrays.copyOfRange(args, 1, args.length), out, err);
        }
        return context
            .getBean(CheckCommand.class)
            .run(Arrays.copyOfRange(args, 1, args.length), out, err);
      }
    }
    err.println("unknown command: " + args[0]);
    return 2;
  }

  @Bean
  DbReadinessCheck dbReadinessCheck(DataSource dataSource) {
    return new DbReadinessCheck(dataSource);
  }
}
