package tally.ledger.api;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import tally.platform.error.ApiError;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;

public final class Cursor {

  private Cursor() {}

  public static String encode(String raw) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static String decode(String cursor) {
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(cursor);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(cursor)) {
        throw invalid();
      }
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (IllegalArgumentException | CharacterCodingException e) {
      throw invalid();
    }
  }

  public static long decodeLong(String cursor) {
    String raw = decode(cursor);
    if (!isDecimal(raw)) {
      throw invalid();
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      throw invalid();
    }
  }

  private static boolean isDecimal(String value) {
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch < '0' || ch > '9') {
        return false;
      }
    }
    return true;
  }

  private static ApiException invalid() {
    return new ApiException(
        ErrorCode.VALIDATION_FAILED,
        "invalid cursor",
        List.of(new ApiError.Detail("cursor", "must be a valid cursor")));
  }
}
