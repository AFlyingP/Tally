package tally.ledger.audit;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tally.ledger.audit.AuditStore.AuditRowData;
import tally.ledger.audit.AuditStore.ChainLink;
import tally.ledger.core.AuditHash;

@Component
public class AuditVerifier {

  private static final DateTimeFormatter HASHED_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

  private final AuditStore store;

  public AuditVerifier(AuditStore store) {
    this.store = store;
  }

  /** Checks links and pending rows in one consistent snapshot; the caller closes the connection. */
  public Report verify(Connection connection) throws SQLException {
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    connection.setReadOnly(true);
    connection.setAutoCommit(false);
    Walk walk = new Walk();
    try {
      Long brokenSeq = null;
      String brokenReason = null;
      try {
        store.walkChain(connection, walk);
      } catch (BrokenLink broken) {
        brokenSeq = broken.seq;
        brokenReason = broken.getMessage();
      }
      long pending = store.pendingCount(connection);
      connection.commit();
      return new Report(walk.sealedRows, pending, brokenSeq, brokenReason);
    } catch (SQLException | RuntimeException exception) {
      connection.rollback();
      throw exception;
    }
  }

  public record Report(long sealedRows, long pendingRows, Long brokenSeq, String brokenReason) {}

  private static final class Walk implements Consumer<ChainLink> {
    private long seq;
    private long sealedRows;
    private byte[] previous = new byte[32];

    @Override
    public void accept(ChainLink link) {
      String reason = failure(link);
      if (reason != null) {
        throw new BrokenLink(link.seq(), reason);
      }
      seq = link.seq();
      previous = link.chainHash();
      sealedRows++;
    }

    private String failure(ChainLink link) {
      if (link.seq() != seq + 1) {
        return "gap";
      }
      AuditRowData row = link.row();
      if (row == null) {
        return "missing audit row";
      }
      byte[] hash =
          AuditHash.row(
              row.id().toString(),
              HASHED_TIME.format(row.occurredAt()),
              row.actor(),
              row.actorRole(),
              row.scopes(),
              row.action(),
              row.resourceType(),
              row.resourceId(),
              row.requestId(),
              row.outcome(),
              row.reason(),
              row.transactionId() == null ? null : row.transactionId().toString());
      if (!Arrays.equals(hash, row.rowHash())) {
        return "row content changed";
      }
      if (!Arrays.equals(link.prevHash(), previous)) {
        return "previous hash mismatch";
      }
      if (!Arrays.equals(link.chainHash(), AuditHash.chain(link.prevHash(), row.rowHash()))) {
        return "chain hash mismatch";
      }
      return null;
    }
  }

  private static final class BrokenLink extends RuntimeException {
    private final long seq;

    private BrokenLink(long seq, String reason) {
      super(reason, null, false, false);
      this.seq = seq;
    }
  }
}
