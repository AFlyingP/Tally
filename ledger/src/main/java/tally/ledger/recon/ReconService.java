package tally.ledger.recon;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Service;
import tally.ledger.api.ReconDto;
import tally.ledger.audit.AuditEntry;
import tally.ledger.audit.AuditWriter;
import tally.ledger.core.BreakClassifier;
import tally.ledger.core.StatementLine;
import tally.ledger.core.StatementParser;
import tally.ledger.payments.ExtbankClient;
import tally.ledger.posting.StrategyRunner;
import tally.platform.error.ApiError;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.id.UuidV7;
import tally.platform.security.Caller;

@Service
public class ReconService {

  public record RunResult(ReconDto.ReconRun run, boolean created) {}

  private final ReconStore store;
  private final ExtbankClient bank;
  private final AuditWriter audit;
  private final StrategyRunner runner;
  private final Clock clock;

  public ReconService(
      ReconStore store, ExtbankClient bank, AuditWriter audit, StrategyRunner runner, Clock clock) {
    this.store = store;
    this.bank = bank;
    this.audit = audit;
    this.runner = runner;
    this.clock = clock;
  }

  public RunResult run(
      Caller caller, LocalDate businessDate, String statementCsvOrNull, String requestId) {
    if (businessDate == null || businessDate.isAfter(LocalDate.now(clock))) {
      throw invalid("business_date", "must be a date not after today");
    }
    if (statementCsvOrNull != null
        && statementCsvOrNull.getBytes(StandardCharsets.UTF_8).length > 10485760) {
      throw invalid("statement_csv", "must be at most 10485760 UTF-8 bytes");
    }
    String csv =
        statementCsvOrNull == null ? bank.statement(businessDate, requestId) : statementCsvOrNull;
    byte[] sha = sha256(csv);
    try {
      return runner
          .execute(tx -> create(tx.connection(), caller, businessDate, csv, sha, requestId))
          .value();
    } catch (UncategorizedSQLException exception) {
      SQLException sql = exception.getSQLException();
      if (!"23505".equals(sql.getSQLState()) || !sql.getMessage().contains("recon_run_file_uq")) {
        throw exception;
      }
      return runner
          .execute(
              tx -> {
                ReconStore.RunRow row =
                    store.findByFile(tx.connection(), businessDate, sha).orElseThrow();
                return new RunResult(toDto(row, store.breaks(tx.connection(), row.id())), false);
              })
          .value();
    }
  }

  public ReconDto.ReconRun get(UUID id) {
    return runner
        .execute(
            tx -> {
              ReconStore.RunRow row =
                  store
                      .find(tx.connection(), id)
                      .orElseThrow(
                          () ->
                              new ApiException(
                                  ErrorCode.NOT_FOUND, "reconciliation run not found"));
              return toDto(row, store.breaks(tx.connection(), id));
            })
        .value();
  }

  public ReconDto.ReconRunList byDate(LocalDate date) {
    return runner
        .execute(
            tx ->
                new ReconDto.ReconRunList(
                    store.byDate(tx.connection(), date).stream()
                        .map(row -> toDto(row, null))
                        .toList()))
        .value();
  }

  private RunResult create(
      Connection connection,
      Caller caller,
      LocalDate date,
      String csv,
      byte[] sha,
      String requestId)
      throws SQLException {
    Optional<ReconStore.RunRow> found = store.findByFile(connection, date, sha);
    if (found.isPresent()) {
      ReconStore.RunRow row = found.orElseThrow();
      return new RunResult(toDto(row, store.breaks(connection, row.id())), false);
    }
    UUID previous = store.latest(connection, date).map(ReconStore.RunRow::id).orElse(null);
    Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    UUID id = UuidV7.next(clock);
    ReconStore.RunRow row;
    List<BreakClassifier.Break> breaks;
    try {
      List<StatementLine> lines = StatementParser.parse(csv);
      List<BreakClassifier.LedgerPayment> payments =
          store.ledgerPayments(
              connection,
              date,
              lines.stream().map(StatementLine::reference).collect(Collectors.toSet()));
      breaks = BreakClassifier.classify(date, lines, payments);
      Set<String> broken =
          breaks.stream().map(BreakClassifier.Break::reference).collect(Collectors.toSet());
      int matched = (int) lines.stream().filter(line -> !broken.contains(line.reference())).count();
      row =
          new ReconStore.RunRow(
              id,
              date,
              sha,
              "COMPLETED",
              null,
              null,
              previous,
              lines.size(),
              payments.size(),
              matched,
              breaks.size(),
              startedAt,
              now());
    } catch (StatementParser.Malformed malformed) {
      breaks = List.of();
      row =
          new ReconStore.RunRow(
              id,
              date,
              sha,
              "FAILED",
              malformed.line(),
              malformed.reason(),
              previous,
              0,
              0,
              0,
              0,
              startedAt,
              now());
    }
    store.insertRun(connection, row);
    store.insertBreaks(connection, id, breaks);
    audit.record(
        connection, AuditEntry.success(caller, "recon.run", "recon_run", id.toString(), requestId));
    return new RunResult(toDto(row, breaks), true);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static byte[] sha256(String csv) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(csv.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static ApiException invalid(String field, String issue) {
    return new ApiException(
        ErrorCode.VALIDATION_FAILED,
        "request is not valid",
        List.of(new ApiError.Detail(field, issue)));
  }

  private static ReconDto.ReconRun toDto(
      ReconStore.RunRow row, List<BreakClassifier.Break> breaks) {
    List<ReconDto.ReconBreak> items =
        breaks == null
            ? null
            : breaks.stream()
                .map(
                    item ->
                        new ReconDto.ReconBreak(
                            item.type().name(),
                            item.reference(),
                            item.lineNumber(),
                            item.ledgerAmount(),
                            item.ledgerCurrency(),
                            item.ledgerDate(),
                            item.statementAmount(),
                            item.statementCurrency(),
                            item.statementDate()))
                .toList();
    return new ReconDto.ReconRun(
        row.id(),
        row.businessDate(),
        row.status(),
        row.failureLine(),
        row.failureReason(),
        row.previousRunId(),
        row.statementLines(),
        row.ledgerPayments(),
        row.matched(),
        row.breakCount(),
        row.startedAt(),
        row.finishedAt(),
        items);
  }
}
