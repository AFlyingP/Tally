package tally.ledger.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AuditHashTest {

  private static final String[] VALUES = {
    "a", "b", "c", null, "e", "f", "g", "h", "i", "j", null, "l"
  };

  @Test
  void rowHashMatchesKnownVector() throws Exception {
    String joined = "a\u001fb\u001fc\u001f\u001fe\u001ff\u001fg\u001fh\u001fi\u001fj\u001f\u001fl";
    byte[] expected =
        MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));

    assertThat(row(VALUES)).isEqualTo(expected);
  }

  @Test
  void rowHashChangesWithEachField() {
    byte[] original = row(VALUES);

    for (int i = 0; i < VALUES.length; i++) {
      String[] changed = VALUES.clone();
      changed[i] = "changed";
      assertThat(row(changed)).as("field %d", i).isNotEqualTo(original);
    }
  }

  @Test
  void chainHashIsShaOfConcatenation() throws Exception {
    byte[] zero = new byte[32];
    byte[] rowHash = row(VALUES);
    byte[] both = Arrays.copyOf(zero, 64);
    System.arraycopy(rowHash, 0, both, 32, 32);

    assertThat(AuditHash.chain(zero, rowHash))
        .isEqualTo(MessageDigest.getInstance("SHA-256").digest(both));
    assertThatThrownBy(() -> AuditHash.chain(new byte[31], rowHash))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AuditHash.chain(zero, new byte[33]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static byte[] row(String[] v) {
    return AuditHash.row(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11]);
  }
}
