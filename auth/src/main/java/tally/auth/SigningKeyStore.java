package tally.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

public class SigningKeyStore {
  private static final String BEGIN = "-----BEGIN PRIVATE KEY-----";
  private static final String END = "-----END PRIVATE KEY-----";

  public static RSAKey loadOrCreate(Path path) {
    if (Files.exists(path)) {
      return read(path);
    }
    Path parent = path.toAbsolutePath().getParent();
    Path temporary = null;
    try {
      Files.createDirectories(parent);
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair pair = generator.generateKeyPair();
      temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
      setPrivatePermissions(temporary);
      Files.writeString(
          temporary, toPem(pair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
      Files.move(
          temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      return buildKey((RSAPublicKey) pair.getPublic(), (RSAPrivateCrtKey) pair.getPrivate());
    } catch (IOException | GeneralSecurityException | JOSEException exception) {
      throw new IllegalStateException("could not create signing key", exception);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The temporary file is already moved on success.
        }
      }
    }
  }

  private static RSAKey read(Path path) {
    try {
      String pem = Files.readString(path, StandardCharsets.US_ASCII).trim();
      if (!pem.startsWith(BEGIN) || !pem.endsWith(END)) {
        throw new GeneralSecurityException("invalid PEM markers");
      }
      String encoded =
          pem.substring(BEGIN.length(), pem.length() - END.length()).replaceAll("\\s", "");
      byte[] der = Base64.getDecoder().decode(encoded);
      KeyFactory factory = KeyFactory.getInstance("RSA");
      java.security.PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
      if (!(privateKey instanceof RSAPrivateCrtKey rsaPrivateKey)) {
        throw new GeneralSecurityException("key is not an RSA CRT key");
      }
      RSAPublicKey publicKey =
          (RSAPublicKey)
              factory.generatePublic(
                  new RSAPublicKeySpec(
                      rsaPrivateKey.getModulus(), rsaPrivateKey.getPublicExponent()));
      return buildKey(publicKey, rsaPrivateKey);
    } catch (IOException
        | GeneralSecurityException
        | IllegalArgumentException
        | JOSEException exception) {
      throw new IllegalArgumentException(
          "invalid configuration: tally.auth.key-path: unreadable key file", exception);
    }
  }

  private static RSAKey buildKey(RSAPublicKey publicKey, RSAPrivateCrtKey privateKey)
      throws GeneralSecurityException, JOSEException {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
    return new RSAKey.Builder(publicKey)
        .privateKey(privateKey)
        .keyID(HexFormat.of().formatHex(digest).substring(0, 16))
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.RS256)
        .build();
  }

  private static String toPem(byte[] encoded) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
    return BEGIN + "\n" + body + "\n" + END + "\n";
  }

  private static void setPrivatePermissions(Path path) throws IOException {
    FileStore store = Files.getFileStore(path);
    if (store.supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(
          path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
  }
}
