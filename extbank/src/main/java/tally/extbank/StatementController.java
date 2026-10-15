package tally.extbank;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;

@RestController
public class StatementController {

  private static final String HEADER = "reference,creditor_account,amount,currency,value_date\n";

  private final DataSource dataSource;
  private final PaymentStore payments;

  public StatementController(DataSource dataSource, PaymentStore payments) {
    this.dataSource = dataSource;
    this.payments = payments;
  }

  @GetMapping("/statements/{date}")
  ResponseEntity<String> statement(@PathVariable String date) throws SQLException {
    LocalDate valueDate;
    try {
      valueDate = LocalDate.parse(date);
    } catch (DateTimeParseException e) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "date is invalid");
    }
    List<ExtbankDto.BankPayment> confirmed;
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      confirmed = payments.confirmedOn(connection, valueDate);
    }
    StringBuilder csv = new StringBuilder(HEADER);
    for (ExtbankDto.BankPayment payment : confirmed) {
      String line = line(payment);
      for (int i = 0; i < payment.confirmations(); i++) {
        csv.append(line).append('\n');
      }
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
        .body(csv.toString());
  }

  private static String line(ExtbankDto.BankPayment payment) {
    return payment.reference()
        + ","
        + payment.creditorAccount()
        + ","
        + payment.amount()
        + ","
        + payment.currency()
        + ","
        + payment.valueDate();
  }
}
