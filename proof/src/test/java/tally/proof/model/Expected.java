package tally.proof.model;

public record Expected(int status, String errorCode) {

  public static Expected ok(int status) {
    return new Expected(status, null);
  }

  public static Expected error(int status, String code) {
    return new Expected(status, code);
  }
}
