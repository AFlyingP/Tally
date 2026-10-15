package tally.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;

public final class ClientDto {

  private ClientDto() {}

  public record Create(
      @NotNull @Pattern(regexp = "^[a-z0-9-]{3,64}$") String clientId,
      @NotNull @Pattern(regexp = "^(service-desk|operator|admin|auditor|compliance)$")
          String role) {}

  public record Created(String clientId, String role, String clientSecret, Instant createdAt) {}

  public record Client(String clientId, String role, Instant createdAt, Instant disabledAt) {}

  public record ClientList(List<Client> items) {}
}
