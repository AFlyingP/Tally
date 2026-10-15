package tally.auth;

import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tally.auth.ClientStore.ClientRow;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;
import tally.platform.security.Caller;
import tally.platform.security.Role;
import tally.platform.web.RequestIdFilter;

@RestController
public class ClientController {

  private final ClientStore store;
  private final DataSource dataSource;
  private final Clock clock;
  private final PasswordEncoder encoder;
  private final SecureRandom random = new SecureRandom();

  public ClientController(
      ClientStore store, DataSource dataSource, Clock clock, PasswordEncoder encoder) {
    this.store = store;
    this.dataSource = dataSource;
    this.clock = clock;
    this.encoder = encoder;
  }

  @PostMapping("/v1/clients")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('SCOPE_clients:manage')")
  @Transactional(rollbackFor = SQLException.class)
  ClientDto.Created create(
      @Valid @RequestBody ClientDto.Create request, @AuthenticationPrincipal Jwt jwt)
      throws SQLException {
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    String secret = HexFormat.of().formatHex(bytes);
    Instant now = clock.instant();
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      Role role = Role.fromWireName(request.role());
      if (!store.insert(
          connection, request.clientId(), encoder.encode(secret), role.wireName(), now)) {
        throw new ApiException(ErrorCode.CLIENT_EXISTS, "client ID is already registered");
      }
      store.event(
          connection,
          request.clientId(),
          "CREATED",
          Caller.from(jwt).clientId(),
          RequestIdFilter.current(),
          now);
      return new ClientDto.Created(request.clientId(), role.wireName(), secret, now);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  @GetMapping("/v1/clients")
  @PreAuthorize("hasAuthority('SCOPE_clients:read')")
  ClientDto.ClientList list() throws SQLException {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      List<ClientDto.Client> clients =
          store.all(connection).stream()
              .map(
                  row ->
                      new ClientDto.Client(
                          row.clientId(), row.role(), row.createdAt(), row.disabledAt()))
              .toList();
      return new ClientDto.ClientList(clients);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  @DeleteMapping("/v1/clients/{clientId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAuthority('SCOPE_clients:manage')")
  @Transactional(rollbackFor = SQLException.class)
  void disable(@PathVariable String clientId, @AuthenticationPrincipal Jwt jwt)
      throws SQLException {
    Instant now = clock.instant();
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      ClientRow row =
          store
              .find(connection, clientId)
              .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "client does not exist"));
      if (row.disabledAt() == null && store.disable(connection, clientId, now) == 1) {
        store.event(
            connection,
            clientId,
            "DISABLED",
            Caller.from(jwt).clientId(),
            RequestIdFilter.current(),
            now);
      }
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }
}
