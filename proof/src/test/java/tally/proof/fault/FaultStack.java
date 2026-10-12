package tally.proof.fault;

import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.awaitility.core.ConditionTimeoutException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.MountableFile;
import tally.ledger.check.InvariantChecker;
import tally.ledger.check.Violation;
import tally.platform.testing.TestIssuer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Containers and stable addresses for exercising ledger failures. */
public final class FaultStack implements AutoCloseable {

  public enum Part {
    POSTGRES,
    TOXIPROXY,
    LEDGER
  }

  private static final String PASSWORD = "test-password";
  private static final String LEDGER_IMAGE = "tally/ledger:dev";
  private static final Duration READY_TIMEOUT = Duration.ofSeconds(120);
  private static final long POSTGRES_MEMORY_MB = 1024;
  private static final long TOXIPROXY_MEMORY_MB = 128;
  private static final long LEDGER_MEMORY_MB = 1024;
  private static final long K6_MEMORY_MB = 512;

  private final DockerClient dockerClient;
  private final Network network;
  private final TestIssuer issuer;
  private final HttpClient http;
  private final Map<Part, Integer> ports;
  private final Map<Part, GenericContainer<?>> containers = new EnumMap<>(Part.class);
  private final Map<Part, Map<String, String>> env = new EnumMap<>(Part.class);
  private final Set<Part> changed = EnumSet.noneOf(Part.class);
  private Proxy proxy;
  private boolean closed;

  private FaultStack(String strategy, DockerClient dockerClient) {
    this.dockerClient = dockerClient;
    ports = freePorts();
    network = Network.newNetwork();
    try {
      issuer = TestIssuer.start();
    } catch (RuntimeException e) {
      network.close();
      throw e;
    }
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    env.put(
        Part.POSTGRES,
        new HashMap<>(
            Map.of(
                "POSTGRES_DB", "tally",
                "POSTGRES_USER", "postgres",
                "POSTGRES_PASSWORD", PASSWORD,
                "LEDGER_OWNER_PASSWORD", PASSWORD,
                "LEDGER_APP_PASSWORD", PASSWORD,
                "MONITOR_DB_PASSWORD", PASSWORD,
                "AUTH_DB_PASSWORD", PASSWORD,
                "EXTBANK_DB_PASSWORD", PASSWORD)));
    env.put(Part.TOXIPROXY, new HashMap<>());
    env.put(
        Part.LEDGER,
        new HashMap<>(
            Map.ofEntries(
                Map.entry("SERVER_PORT", "8080"),
                Map.entry("TALLY_LEDGER_ROLES", "api"),
                Map.entry("TALLY_LEDGER_STRATEGY", strategy),
                Map.entry("TALLY_DB_URL", "jdbc:postgresql://toxiproxy:8666/tally"),
                Map.entry("TALLY_DB_USER", "ledger_app"),
                Map.entry("TALLY_DB_PASSWORD", PASSWORD),
                Map.entry("TALLY_DB_OWNER_USER", "ledger_owner"),
                Map.entry("TALLY_DB_OWNER_PASSWORD", PASSWORD),
                Map.entry("JAVA_TOOL_OPTIONS", "-Xmx640m"),
                Map.entry("TALLY_AUTH_ISSUER", issuer.issuer()),
                Map.entry(
                    "TALLY_AUTH_JWKS_URI",
                    issuer.jwksUri().replace("localhost", "host.testcontainers.internal")))));
  }

  public static FaultStack start(String strategy, Set<Part> parts) {
    Set<Part> requested = Set.copyOf(parts);
    DockerClient dockerClient = DockerClientFactory.instance().client();
    if (requested.contains(Part.LEDGER)) {
      requireLedgerImage(dockerClient);
    }
    FaultStack stack = new FaultStack(strategy, dockerClient);
    try {
      for (Part part : Part.values()) {
        if (requested.contains(part)) {
          if (part == Part.LEDGER) {
            Testcontainers.exposeHostPorts(URI.create(stack.issuer.jwksUri()).getPort());
          }
          GenericContainer<?> container = stack.createContainer(part);
          stack.containers.put(part, container);
          container.start();
          stack.awaitReady(alias(part), READY_TIMEOUT);
          if (part == Part.TOXIPROXY) {
            stack.restoreProxy();
          }
        }
      }
      return stack;
    } catch (RuntimeException e) {
      try {
        stack.close();
      } catch (RuntimeException cleanupFailure) {
        e.addSuppressed(cleanupFailure);
      }
      throw e;
    }
  }

  public void kill(String alias) {
    GenericContainer<?> container = container(part(alias));
    dockerClient.killContainerCmd(container.getContainerId()).withSignal("KILL").exec();
  }

  public void start(String alias) {
    Part part = part(alias);
    GenericContainer<?> container = container(part);
    if (changed.contains(part)) {
      container.stop();
      container = createContainer(part);
      containers.put(part, container);
      container.start();
      changed.remove(part);
    } else {
      dockerClient.startContainerCmd(container.getContainerId()).exec();
    }
    awaitReady(alias, READY_TIMEOUT);
    if (part == Part.TOXIPROXY) {
      restoreProxy();
    }
  }

  public void restart(String alias, int stopTimeoutSeconds) {
    Part part = part(alias);
    GenericContainer<?> container = container(part);
    dockerClient
        .restartContainerCmd(container.getContainerId())
        .withTimeout(stopTimeoutSeconds)
        .exec();
    awaitReady(alias, READY_TIMEOUT);
    if (part == Part.TOXIPROXY) {
      restoreProxy();
    }
  }

  /** Applies an environment value when the container is next started. */
  public void withEnv(String alias, String key, String value) {
    Part part = part(alias);
    GenericContainer<?> container = container(part);
    env.get(part).put(Objects.requireNonNull(key), Objects.requireNonNull(value));
    if (!Objects.equals(container.getEnvMap().get(key), value)) {
      changed.add(part);
    }
  }

  public Proxy toxiproxy() {
    container(Part.TOXIPROXY);
    return proxy;
  }

  public String ledgerUrl() {
    container(Part.LEDGER);
    return "http://localhost:" + ports.get(Part.LEDGER);
  }

  public Connection jdbc() throws SQLException {
    return DriverManager.getConnection(jdbcUrl(), "ledger_app", PASSWORD);
  }

  public Connection superuserJdbc() throws SQLException {
    return DriverManager.getConnection(jdbcUrl(), "postgres", PASSWORD);
  }

  public List<Violation> checker() throws SQLException {
    try (Connection connection = jdbc()) {
      return new InvariantChecker().check(connection);
    }
  }

  public TestIssuer issuer() {
    return issuer;
  }

  public static Map<String, Long> containerLimitsMb() {
    Map<String, Long> limits = new LinkedHashMap<>();
    limits.put("postgres", POSTGRES_MEMORY_MB);
    limits.put("toxiproxy", TOXIPROXY_MEMORY_MB);
    limits.put("ledger", LEDGER_MEMORY_MB);
    limits.put("k6", K6_MEMORY_MB);
    return Collections.unmodifiableMap(limits);
  }

  public K6Result runK6(Map<String, String> env, Path accountsFile) {
    int durationSeconds = Integer.parseInt(env.get("DURATION"));
    Map<String, String> containerEnv = new HashMap<>(env);
    containerEnv.put("BASE_URL", "http://ledger:8080");
    GenericContainer<?> k6 =
        new GenericContainer<>("grafana/k6:1.8.1")
            .withNetwork(network)
            .withEnv(containerEnv)
            .withCopyFileToContainer(
                MountableFile.forHostPath(
                    Path.of(System.getProperty("proof.root", ".."), "load/transfer.js").toString()),
                "/scripts/transfer.js")
            .withCopyFileToContainer(
                MountableFile.forHostPath(accountsFile.toString()), "/data/accounts.json")
            .withCommand("run", "/scripts/transfer.js")
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy()
                    .withTimeout(Duration.ofSeconds(durationSeconds + 60L)))
            .withCreateContainerCmdModifier(
                command -> {
                  command.withUser("0:0");
                  command.getHostConfig().withMemory(K6_MEMORY_MB * 1024 * 1024);
                });
    try {
      try {
        k6.start();
      } catch (RuntimeException e) {
        if (k6.getContainerId() != null) {
          Long exitCode =
              dockerClient
                  .inspectContainerCmd(k6.getContainerId())
                  .exec()
                  .getState()
                  .getExitCodeLong();
          if (exitCode != null && exitCode != 0) {
            String logs = k6.getLogs();
            String lastLogs = logs.substring(Math.max(0, logs.length() - 2_000));
            throw new IllegalStateException("k6 exited with code " + exitCode + ": " + lastLogs, e);
          }
        }
        throw e;
      }
      Long exitCode =
          dockerClient.inspectContainerCmd(k6.getContainerId()).exec().getState().getExitCodeLong();
      if (exitCode != null && exitCode != 0) {
        String logs = k6.getLogs();
        String lastLogs = logs.substring(Math.max(0, logs.length() - 2_000));
        throw new IllegalStateException("k6 exited with code " + exitCode + ": " + lastLogs);
      }
      Path summaryFile;
      try {
        summaryFile = Files.createTempFile("k6-summary-", ".json");
      } catch (IOException e) {
        throw new UncheckedIOException("failed to create k6 summary", e);
      }
      try {
        k6.copyFileFromContainer(
            "/data/summary.json",
            source -> Files.copy(source, summaryFile, StandardCopyOption.REPLACE_EXISTING));
        JsonNode summary = JsonMapper.builder().build().readTree(summaryFile.toFile());
        return new K6Result(
            summary.path("posted").asLong(),
            summary.path("insufficient").asLong(),
            summary.path("exhausted").asLong(),
            summary.path("unexpected").asLong(),
            summary.path("retries").asLong(),
            summary.path("p50_ms").asDouble(),
            summary.path("p99_ms").asDouble(),
            summary.path("duration_s").asDouble());
      } finally {
        try {
          Files.deleteIfExists(summaryFile);
        } catch (IOException e) {
          throw new UncheckedIOException("failed to delete k6 summary", e);
        }
      }
    } finally {
      k6.stop();
    }
  }

  public record K6Result(
      long posted,
      long insufficient,
      long exhausted,
      long unexpected,
      long retries,
      double p50Ms,
      double p99Ms,
      double durationS) {}

  public void awaitReady(String alias, Duration timeout) {
    Part part = part(alias);
    container(part);
    try {
      await()
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(250))
          .atMost(timeout)
          .until(
              () ->
                  switch (part) {
                    case POSTGRES -> postgresReady();
                    case TOXIPROXY ->
                        httpReady("http://localhost:" + ports.get(Part.TOXIPROXY) + "/version");
                    case LEDGER -> httpReady(ledgerUrl() + "/ready");
                  });
    } catch (ConditionTimeoutException e) {
      throw new IllegalStateException("container not ready: " + alias, e);
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    List<Runnable> cleanup = new ArrayList<>();
    for (Part part : List.of(Part.values()).reversed()) {
      GenericContainer<?> container = containers.get(part);
      if (container != null) {
        cleanup.add(container::stop);
      }
    }
    cleanup.add(network::close);
    cleanup.add(issuer::close);
    cleanup.add(http::close);
    RuntimeException failure = null;
    for (Runnable action : cleanup) {
      try {
        action.run();
      } catch (RuntimeException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    containers.clear();
    closed = true;
    if (failure != null) {
      throw failure;
    }
  }

  private GenericContainer<?> createContainer(Part part) {
    Map<String, String> environment = env.get(part);
    GenericContainer<?> container =
        switch (part) {
          case POSTGRES ->
              new PostgreSQLContainer("postgres:16.15-alpine")
                  .withDatabaseName(environment.get("POSTGRES_DB"))
                  .withUsername(environment.get("POSTGRES_USER"))
                  .withPassword(environment.get("POSTGRES_PASSWORD"))
                  .withCommand(
                      "postgres", "-c", "max_connections=200", "-c", "shared_buffers=256MB")
                  .withCopyFileToContainer(
                      MountableFile.forHostPath(
                          Path.of(System.getProperty("proof.root", ".."), "deploy/postgres/init.sh")
                              .toString(),
                          0755),
                      "/docker-entrypoint-initdb.d/init.sh");
          case TOXIPROXY ->
              new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0").withExposedPorts(8474);
          case LEDGER ->
              new GenericContainer<>(LEDGER_IMAGE)
                  .withExposedPorts(8080)
                  .waitingFor(
                      Wait.forHttp("/ready").forStatusCode(200).withStartupTimeout(READY_TIMEOUT));
        };
    int containerPort =
        switch (part) {
          case POSTGRES -> 5432;
          case TOXIPROXY -> 8474;
          case LEDGER -> 8080;
        };
    long memoryMb =
        switch (part) {
          case POSTGRES -> POSTGRES_MEMORY_MB;
          case TOXIPROXY -> TOXIPROXY_MEMORY_MB;
          case LEDGER -> LEDGER_MEMORY_MB;
        };
    container
        .withNetwork(network)
        .withNetworkAliases(alias(part))
        .withEnv(environment)
        .withCreateContainerCmdModifier(
            command ->
                command
                    .getHostConfig()
                    .withMemory(memoryMb * 1024 * 1024)
                    .withPortBindings(
                        new PortBinding(
                            Ports.Binding.bindPort(ports.get(part)),
                            new ExposedPort(containerPort))));
    return container;
  }

  private void restoreProxy() {
    ToxiproxyClient client = new ToxiproxyClient("localhost", ports.get(Part.TOXIPROXY));
    try {
      proxy = client.getProxyOrNull("pg");
      if (proxy == null) {
        proxy = client.createProxy("pg", "0.0.0.0:8666", "postgres:5432");
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to configure database proxy", e);
    }
  }

  private boolean postgresReady() {
    try (Connection connection = superuserJdbc();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT 1")) {
      return result.next() && result.getInt(1) == 1;
    } catch (SQLException e) {
      return false;
    }
  }

  private boolean httpReady(String url) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(1)).GET().build();
    try {
      return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("readiness check interrupted", e);
    }
  }

  private String jdbcUrl() {
    container(Part.POSTGRES);
    return "jdbc:postgresql://localhost:" + ports.get(Part.POSTGRES) + "/tally";
  }

  private GenericContainer<?> container(Part part) {
    GenericContainer<?> container = containers.get(part);
    if (container == null) {
      throw new IllegalStateException("container not started: " + alias(part));
    }
    return container;
  }

  private static Part part(String alias) {
    return switch (alias) {
      case "postgres" -> Part.POSTGRES;
      case "toxiproxy" -> Part.TOXIPROXY;
      case "ledger" -> Part.LEDGER;
      default -> throw new IllegalArgumentException("unknown container alias: " + alias);
    };
  }

  private static String alias(Part part) {
    return part.name().toLowerCase(Locale.ROOT);
  }

  private static Map<Part, Integer> freePorts() {
    try (ServerSocket postgres = new ServerSocket(0);
        ServerSocket toxiproxy = new ServerSocket(0);
        ServerSocket ledger = new ServerSocket(0)) {
      return Map.of(
          Part.POSTGRES, postgres.getLocalPort(),
          Part.TOXIPROXY, toxiproxy.getLocalPort(),
          Part.LEDGER, ledger.getLocalPort());
    } catch (IOException e) {
      throw new UncheckedIOException("failed to reserve host ports", e);
    }
  }

  private static void requireLedgerImage(DockerClient dockerClient) {
    try {
      dockerClient.inspectImageCmd(LEDGER_IMAGE).exec();
    } catch (NotFoundException e) {
      throw new IllegalStateException(
          "image tally/ledger:dev not found, run: sh task.sh images", e);
    }
  }
}
