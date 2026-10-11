package tally.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tally.platform.error.ApiError;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;

class CursorTest {

  @Test
  void roundTripsLongIds() {
    assertThat(Cursor.decodeLong(Cursor.encode("17"))).isEqualTo(17);
    assertThat(Cursor.encode("17")).isEqualTo("MTc");
  }

  @Test
  void rejectsGarbage() {
    assertInvalidCursor(() -> Cursor.decode("***"));
    assertInvalidCursor(() -> Cursor.decodeLong(Cursor.encode("abc")));
    assertInvalidCursor(() -> Cursor.decodeLong(Cursor.encode("-1")));
  }

  private static void assertInvalidCursor(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
              assertThat(exception.details()).extracting(ApiError.Detail::field).contains("cursor");
            });
  }
}
