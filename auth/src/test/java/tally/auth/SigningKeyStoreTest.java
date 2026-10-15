package tally.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeyStoreTest {
  @TempDir Path directory;

  @Test
  void createsKeyFileWhenAbsent() throws Exception {
    Path path = directory.resolve("signing-key.pem");

    RSAKey key = SigningKeyStore.loadOrCreate(path);

    assertNotNull(key);
    assertTrue(Files.exists(path));
    assertTrue(Files.readString(path).startsWith("-----BEGIN PRIVATE KEY-----"));
  }

  @Test
  void reloadsTheSameKey() throws Exception {
    Path path = directory.resolve("signing-key.pem");

    RSAKey first = SigningKeyStore.loadOrCreate(path);
    RSAKey second = SigningKeyStore.loadOrCreate(path);

    assertEquals(first.getKeyID(), second.getKeyID());
    assertEquals(first.toRSAPublicKey().getModulus(), second.toRSAPublicKey().getModulus());
  }

  @Test
  void rejectsCorruptFile() throws Exception {
    Path path = directory.resolve("signing-key.pem");
    Files.writeString(path, "corrupt");

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> SigningKeyStore.loadOrCreate(path));

    assertTrue(exception.getMessage().contains("invalid configuration: tally.auth.key-path"));
  }

  @Test
  void createsMissingParentDirectories() {
    Path path = directory.resolve("nested/keys/signing-key.pem");

    RSAKey key = SigningKeyStore.loadOrCreate(path);

    assertNotNull(key);
    assertTrue(Files.exists(path));
  }
}
