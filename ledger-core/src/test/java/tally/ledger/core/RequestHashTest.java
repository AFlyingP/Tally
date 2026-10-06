package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

class RequestHashTest {

  @Test
  void hashesMethodPathAndBodyWithNewlines() throws Exception {
    byte[] expected =
        MessageDigest.getInstance("SHA-256")
            .digest("POST\n/v1/transfers\n{}".getBytes(StandardCharsets.UTF_8));
    byte[] original = RequestHash.of("POST", "/v1/transfers", "{}");

    assertThat(original).isEqualTo(expected);
    assertThat(RequestHash.of("PUT", "/v1/transfers", "{}")).isNotEqualTo(original);
    assertThat(RequestHash.of("POST", "/v1/deposits", "{}")).isNotEqualTo(original);
    assertThat(RequestHash.of("POST", "/v1/transfers", "{\"amount\":1}")).isNotEqualTo(original);
  }
}
