package tally.auth;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {"tally.auth", "tally.platform"})
@ConfigurationPropertiesScan
public class AuthApplication {

  public static SpringApplicationBuilder builder() {
    return new SpringApplicationBuilder(AuthApplication.class)
        .properties("spring.config.name=auth");
  }

  public static void main(String[] args) {
    builder().run(args);
  }
}
