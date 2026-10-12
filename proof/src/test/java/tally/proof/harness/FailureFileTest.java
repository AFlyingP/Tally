package tally.proof.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tally.proof.gen.Op;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FailureFileTest {

  @Test
  void writesShrunkSequenceAsJson() throws IOException {
    Path file = FailureFile.write("p", "1", "pessimistic", List.of(new Op.Deposit(0, 5)));
    assertThat(file).isRegularFile();
    assertThat(file)
        .isEqualTo(
            Path.of(System.getProperty("proof.root", ".."), "proof/build/failures/p-1.json"));
    JsonMapper json = JsonMapper.builder().build();
    JsonNode root = json.readTree(Files.readString(file));
    assertThat(root.get("property").asString()).isEqualTo("p");
    assertThat(root.get("seed").asString()).isEqualTo("1");
    assertThat(root.get("strategy").asString()).isEqualTo("pessimistic");
    assertThat(root.get("ops").size()).isEqualTo(1);
    assertThat(root.get("ops").get(0))
        .isEqualTo(json.readTree("{\"type\":\"Deposit\",\"account\":0,\"amount\":5}"));
  }
}
