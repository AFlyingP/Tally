package tally.proof.naive;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tally.ledger.store.AccountStore;

@Configuration
public class NaiveConfig {

  @Bean
  public NaiveStrategy naiveStrategy(DataSource dataSource, Clock clock, AccountStore store) {
    return new NaiveStrategy(dataSource, clock, store);
  }
}
