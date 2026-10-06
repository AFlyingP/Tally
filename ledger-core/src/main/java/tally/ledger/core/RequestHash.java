package tally.ledger.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RequestHash {

  private RequestHash() {}

  public static byte[] of(String method, String path, String canonicalJson) {
    String request = method + "\n" + path + "\n" + canonicalJson;
    return sha256().digest(request.getBytes(StandardCharsets.UTF_8));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
