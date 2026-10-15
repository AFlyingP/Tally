package tally.ledger.audit;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tally.ledger.config.JobConfig.Job;
import tally.ledger.config.LedgerProperties;
import tally.ledger.core.AuditHash;
import tally.ledger.store.AdvisoryLock;

@Component
@ConditionalOnExpression("'${tally.ledger.roles}'.contains('sealer')")
public final class AuditSealer implements Job, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(AuditSealer.class);
  private static final int BATCH_SIZE = 1000;

  private final DataSource dataSource;
  private final AuditStore store;
  private final LedgerProperties properties;
  private final Clock clock;
  private final AdvisoryLock lock;
  private boolean fullBatch;

  public AuditSealer(
      DataSource dataSource, AuditStore store, LedgerProperties properties, Clock clock) {
    this.dataSource = dataSource;
    this.store = store;
    this.properties = properties;
    this.clock = clock;
    lock = new AdvisoryLock(dataSource, 7411002L);
  }

  @Override
  public String name() {
    return "sealer";
  }

  @Override
  public String role() {
    return "sealer";
  }

  @Override
  public long pollMs() {
    return fullBatch ? 0 : properties.sealer().pollMs();
  }

  @Override
  public int runOnce() {
    fullBatch = false;
    try {
      if (!lock.tryAcquire()) {
        return 0;
      }
      try (Connection connection = dataSource.getConnection()) {
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
        try {
          List<UUID> ids = store.takePending(connection, BATCH_SIZE);
          if (ids.isEmpty()) {
            connection.commit();
            return 0;
          }
          List<AuditStore.HashRow> rows = store.rowHashes(connection, ids);
          AuditStore.ChainHead head =
              store.head(connection).orElseGet(() -> new AuditStore.ChainHead(0, new byte[32]));
          long seq = head.seq();
          byte[] previous = head.chainHash();
          Instant sealedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
          for (AuditStore.HashRow row : rows) {
            byte[] hash = AuditHash.chain(previous, row.rowHash());
            store.appendChain(connection, ++seq, row.id(), previous, hash, sealedAt);
            previous = hash;
          }
          connection.commit();
          fullBatch = rows.size() == BATCH_SIZE;
          return rows.size();
        } catch (SQLException | RuntimeException exception) {
          connection.rollback();
          throw exception;
        }
      }
    } catch (SQLException | RuntimeException exception) {
      log.error("sealer failed", exception);
      return 0;
    }
  }

  @Override
  @PreDestroy
  public void close() {
    lock.release();
  }
}
