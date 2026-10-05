package tally.ledger.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The hashes that make the audit log tamper-evident. */
public final class AuditHash {

  private static final String SEPARATOR = "\u001f";
  private static final int HASH_BYTES = 32;

  private AuditHash() {}

  /** Hash of one audit row: its 12 values joined by U+001F, a null counting as empty. */
  public static byte[] row(
      String id,
      String occurredAt,
      String actor,
      String actorRole,
      String scopes,
      String action,
      String resourceType,
      String resourceId,
      String requestId,
      String outcome,
      String reason,
      String transactionId) {
    String[] values = {
      id,
      occurredAt,
      actor,
      actorRole,
      scopes,
      action,
      resourceType,
      resourceId,
      requestId,
      outcome,
      reason,
      transactionId
    };
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        joined.append(SEPARATOR);
      }
      if (values[i] != null) {
        joined.append(values[i]);
      }
    }
    return sha256().digest(joined.toString().getBytes(StandardCharsets.UTF_8));
  }

  /** Hash that links a row to everything before it; the chain starts from 32 zero bytes. */
  public static byte[] chain(byte[] previousChainHash, byte[] rowHash) {
    if (previousChainHash.length != HASH_BYTES || rowHash.length != HASH_BYTES) {
      throw new IllegalArgumentException("both hashes must be 32 bytes");
    }
    MessageDigest digest = sha256();
    digest.update(previousChainHash);
    digest.update(rowHash);
    return digest.digest();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
