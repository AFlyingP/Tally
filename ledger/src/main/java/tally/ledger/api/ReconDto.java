package tally.ledger.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReconDto {

  private ReconDto() {}

  public record Run(LocalDate businessDate, String statementCsv) {}

  public record ReconRun(
      UUID id,
      LocalDate businessDate,
      String status,
      Integer failureLine,
      String failureReason,
      UUID previousRunId,
      int statementLines,
      int ledgerPayments,
      int matched,
      int breakCount,
      Instant startedAt,
      Instant finishedAt,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<ReconBreak> breaks) {}

  public record ReconBreak(
      String type,
      String reference,
      Integer lineNumber,
      Long ledgerAmount,
      String ledgerCurrency,
      LocalDate ledgerDate,
      Long statementAmount,
      String statementCurrency,
      LocalDate statementDate) {}

  public record ReconRunList(List<ReconRun> items) {}
}
