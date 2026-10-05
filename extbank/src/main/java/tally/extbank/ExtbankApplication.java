package tally.extbank;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tally.platform.web.DbReadinessCheck;

@SpringBootApplication(scanBasePackages = {"tally.extbank", "tally.platform"})
@ConfigurationPropertiesScan
public class ExtbankApplication {

  public static SpringApplicationBuilder builder() {
    return new SpringApplicationBuilder(ExtbankApplication.class)
        .properties("spring.config.name=extbank");
  }

  public static void main(String[] args) {
    builder().run(args);
  }

  @Bean
  DbReadinessCheck dbReadinessCheck(DataSource dataSource) {
    return new DbReadinessCheck(dataSource);
  }

  // The simulated bank takes no access tokens, so the default login protection is turned off.
  @Bean
  SecurityFilterChain openSecurity(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .build();
  }
}
