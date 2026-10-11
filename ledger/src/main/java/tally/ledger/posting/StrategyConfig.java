package tally.ledger.posting;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tally.ledger.config.LedgerProperties;

@Configuration
public class StrategyConfig {

  private static final Logger log = LoggerFactory.getLogger(StrategyConfig.class);

  @Bean
  @Primary
  public PostingStrategy selectedStrategy(List<PostingStrategy> all, LedgerProperties properties) {
    String value = properties.strategy();
    PostingStrategy selected =
        all.stream()
            .filter(strategy -> strategy.name().equals(value))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "invalid configuration: tally.ledger.strategy: no strategy named "
                            + value));
    log.info("posting strategy: {}", selected.name());
    return selected;
  }
}
