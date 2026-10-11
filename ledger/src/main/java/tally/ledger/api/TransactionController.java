package tally.ledger.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tally.ledger.store.TransactionStore;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;

@RestController
public class TransactionController {

  private final DataSource dataSource;
  private final TransactionStore transactions;

  public TransactionController(DataSource dataSource, TransactionStore transactions) {
    this.dataSource = dataSource;
    this.transactions = transactions;
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
}
