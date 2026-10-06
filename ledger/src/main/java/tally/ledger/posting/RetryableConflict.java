package tally.ledger.posting;

public class RetryableConflict extends RuntimeException {

  public RetryableConflict(String cause) {
    super(cause);
  }
}
