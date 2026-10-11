package tally.ledger.api;

import jakarta.validation.Valid;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tally.ledger.idempotency.IdempotentExecutor.Result;
import tally.ledger.money.ReversalService;
import tally.ledger.store.TransactionStore;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.security.Caller;

@RestController
public class TransactionController {

  private final DataSource dataSource;
  private final TransactionStore transactions;
  private final ReversalService reversals;

  public TransactionController(
      DataSource dataSource, TransactionStore transactions, ReversalService reversals) {
    this.dataSource = dataSource;
    this.transactions = transactions;
    this.reversals = reversals;
  }

  @GetMapping("/v1/transactions/{id}")
  @PreAuthorize("hasAuthority('SCOPE_transactions:read')")
  TransactionDto.Transaction get(@PathVariable UUID id) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(true);
      return transactions
          .find(connection, id)
          .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "transaction not found"));
    }
  }

  @PostMapping("/v1/transactions/{id}/reversal")
  @PreAuthorize("hasAuthority('SCOPE_transactions:reverse')")
  ResponseEntity<String> reverse(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody(required = false) MoneyDto.Reversal request)
      throws SQLException {
    return response(reversals.reverse(Caller.from(jwt), key, id, request));
  }

  private static ResponseEntity<String> response(Result result) {
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotent-Replay", Boolean.toString(result.replay()))
        .header("X-Tally-Attempts", Integer.toString(result.attempts()))
        .body(result.body());
  }
}
