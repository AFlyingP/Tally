package tally.extbank;

import jakarta.validation.Valid;
import java.sql.SQLException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;

@RestController
public class PaymentController {

  private final PaymentService payments;

  public PaymentController(PaymentService payments) {
    this.payments = payments;
  }

  @PostMapping("/payments")
  ResponseEntity<ExtbankDto.BankPayment> submit(@Valid @RequestBody ExtbankDto.Submit request) {
    PaymentService.SubmitResult result = payments.submit(request);
    if (result.status() == 504) {
      return ResponseEntity.status(504).build();
    }
    return ResponseEntity.status(result.status()).body(result.body());
  }

  @GetMapping("/payments/{reference}")
  ExtbankDto.BankPayment get(@PathVariable String reference) throws SQLException {
    return payments
        .get(reference)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "payment not found"));
  }
}
