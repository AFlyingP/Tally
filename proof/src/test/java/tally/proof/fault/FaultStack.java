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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.awaitility.core.ConditionTimeoutException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.MountableFile;
import tally.ledger.check.InvariantChecker;
import tally.ledger.check.TopicCheck;
import tally.ledger.check.Violation;
import tally.ledger.outbox.OutboxStore;
import tally.platform.testing.TestIssuer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Containers and stable addresses for exercising ledger failures. */
public final class FaultStack implements AutoCloseable {

  public static final String MONITOR_TOPIC = "tally.monitor-seeded.v1";

  public enum Part {
    POSTGRES,
    TOXIPROXY,
    LEDGER,
    KAFKA,
    MONITOR,
    EXTBANK,
    WORKER,
    WORKER2
  }

  private static final String PASSWORD = "test-password";
  private static final String LEDGER_IMAGE = "tally/ledger:dev";
  private static final String MONITOR_IMAGE = "tally/monitor:dev";
  private static final String EXTBANK_IMAGE = "tally/extbank:dev";
  private static final String KAFKA_IMAGE = "apache/kafka:4.2.2";
  private static final String API_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final Duration READY_TIMEOUT = Duration.ofSeconds(120);
  private static final long POSTGRES_MEMORY_MB = 1024;
  private static final long TOXIPROXY_MEMORY_MB = 128;
  private static final long LEDGER_MEMORY_MB = 1024;
  private static final long KAFKA_MEMORY_MB = 1024;
  private static final long MONITOR_MEMORY_MB = 512;
  private static final long EXTBANK_MEMORY_MB = 384;
  private static final long WORKER_MEMORY_MB = 768;
  private static final long K6_MEMORY_MB = 512;
  private static final Logger LOGGER = Logger.getLogger(FaultStack.class.getName());
  private static final WaitStrategy NO_WAIT =
      new WaitStrategy() {
        @Override
        public void waitUntilReady(WaitStrategyTarget target) {}

        @Override
        public WaitStrategy withStartupTimeout(Duration startupTimeout) {
          return this;
        }
      };

  private final DockerClient dockerClient;
  private final Network network;
  private final TestIssuer issuer;
  private final HttpClient http;
  private final Map<Part, Integer> ports;
  private final Map<Part, ServerSocket> reservations;
  private final Map<Part, GenericContainer<?>> containers = new EnumMap<>(Part.class);
  private final Map<Part, Map<String, String>> env = new EnumMap<>(Part.class);
  private final Set<Part> changed = EnumSet.noneOf(Part.class);
  private Proxy proxy;
  private boolean closed;

  private FaultStack(String strategy, DockerClient dockerClient) {
    this.dockerClient = dockerClient;
    reservations = reservePorts();
    ports = portMap(reservations);
    network = Network.newNetwork();
    try {
      issuer = TestIssuer.start();
    } catch (RuntimeException e) {
      network.close();
      closeReservations();
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
        Part.KAFKA,
        new HashMap<>(
            Map.ofEntries(
                Map.entry("CLUSTER_ID", "5L6g3nShT-eMCtK--X86sw"),
                Map.entry("KAFKA_NODE_ID", "1"),
                Map.entry("KAFKA_PROCESS_ROLES", "broker,controller"),
                Map.entry("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093,HOST://:29092"),
                Map.entry(
                    "KAFKA_ADVERTISED_LISTENERS",
                    "PLAINTEXT://kafka:9092,HOST://localhost:" + ports.get(Part.KAFKA)),
                Map.entry(
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                    "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,HOST:PLAINTEXT"),
                Map.entry("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@kafka:9093"),
                Map.entry("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER"),
                Map.entry("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT"),
                Map.entry("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1"),
                Map.entry("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1"),
                Map.entry("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1"),
                Map.entry("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0"),
                Map.entry("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false"),
                Map.entry("KAFKA_LOG_DIRS", "/var/lib/kafka/data"),
                Map.entry("KAFKA_HEAP_OPTS", "-Xms512m -Xmx512m"))));
    env.put(
        Part.EXTBANK,
        new HashMap<>(
            Map.of(
                "SERVER_PORT", "8083",
                "TALLY_DB_URL", "jdbc:postgresql://postgres:5432/tally",
                "TALLY_DB_USER", "extbank_app",
                "TALLY_DB_PASSWORD", PASSWORD,
                "JAVA_TOOL_OPTIONS", "-Xmx192m",
                "EXTBANK_API_KEY", API_KEY)));
    env.put(
        Part.MONITOR,
        new HashMap<>(
            Map.ofEntries(
                Map.entry("SERVER_PORT", "8082"),
                Map.entry("TALLY_DB_URL", "jdbc:postgresql://postgres:5432/tally"),
                Map.entry("TALLY_DB_USER", "monitor_app"),
                Map.entry("TALLY_DB_PASSWORD", PASSWORD),
                Map.entry("TALLY_KAFKA_BOOTSTRAP", "kafka:9092"),
                Map.entry("TALLY_KAFKA_TOPIC", MONITOR_TOPIC),
                Map.entry(
                    "TALLY_AUTH_JWKS_URI",
                    issuer.jwksUri().replace("localhost", "host.testcontainers.internal")),
                Map.entry("TALLY_AUTH_ISSUER", issuer.issuer()),
                Map.entry("JAVA_TOOL_OPTIONS", "-Xmx288m"))));
    for (Part worker : List.of(Part.WORKER, Part.WORKER2)) {
      env.put(
          worker,
          new HashMap<>(
              Map.ofEntries(
                  Map.entry("SERVER_PORT", "8084"),
                  Map.entry("TALLY_LEDGER_ROLES", "scheduler,orchestrator,relay,expiry"),
                  Map.entry("TALLY_LEDGER_STRATEGY", strategy),
                  Map.entry("TALLY_DB_URL", "jdbc:postgresql://postgres:5432/tally"),
                  Map.entry("TALLY_DB_USER", "ledger_app"),
                  Map.entry("TALLY_DB_PASSWORD", PASSWORD),
                  Map.entry("TALLY_DB_OWNER_USER", "ledger_owner"),
                  Map.entry("TALLY_DB_OWNER_PASSWORD", PASSWORD),
                  Map.entry("TALLY_DB_POOL_SIZE", "16"),
                  Map.entry("TALLY_KAFKA_BOOTSTRAP", "kafka:9092"),
                  Map.entry("TALLY_EXTBANK_URL", "http://extbank:8083"),
                  Map.entry("EXTBANK_API_KEY", API_KEY),
                  Map.entry("TALLY_PAYMENT_STATUS_BACKOFF_MS", "100,100,200,200,300,300,400,400"),
                  Map.entry("TALLY_EXTBANK_TIMEOUT_MS", "300"),
                  Map.entry("TALLY_SCHEDULER_POLL_MS", "100"),
                  Map.entry("TALLY_RELAY_POLL_MS", "50"),
                  Map.entry("TALLY_PAYMENT_LEASE_MS", "2000"),
                  Map.entry("JAVA_TOOL_OPTIONS", "-Xmx448m"))));
    }
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
    if (requested.contains(Part.LEDGER)
        || requested.contains(Part.WORKER)
        || requested.contains(Part.WORKER2)) {
      requireLedgerImage(dockerClient);
    }
    if (requested.contains(Part.EXTBANK)) {
      requireImage(dockerClient, EXTBANK_IMAGE);
    }
    if (requested.contains(Part.MONITOR)) {
      requireImage(dockerClient, MONITOR_IMAGE);
    }
    FaultStack stack = new FaultStack(strategy, dockerClient);
    try {
      if (requested.contains(Part.LEDGER) || requested.contains(Part.MONITOR)) {
        Testcontainers.exposeHostPorts(URI.create(stack.issuer.jwksUri()).getPort());
      }
      for (Part part : Part.values()) {
        if (requested.contains(part)) {
          stack.releaseReservation(part);
          try {
            GenericContainer<?> container = stack.createContainer(part);
            stack.containers.put(part, container);
            if (part == Part.WORKER || part == Part.WORKER2) {
              continue;
            }
            container.start();
            stack.awaitReady(alias(part), READY_TIMEOUT);
            if (part == Part.TOXIPROXY) {
              stack.restoreProxy();
            }
          } finally {
            stack.reserveIfStopped(part);
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
    Part part = part(alias);
    GenericContainer<?> container = container(part);
    dockerClient.killContainerCmd(container.getContainerId()).withSignal("KILL").exec();
    awaitExit(alias, Duration.ofSeconds(60));
  }

  public void start(String alias) {
    Part part = startContainer(alias);
    if (part == Part.WORKER || part == Part.WORKER2) {
      awaitWorkerReadyOrHalt(alias, READY_TIMEOUT);
    } else {
      awaitReady(alias, READY_TIMEOUT);
    }
    if (part == Part.TOXIPROXY) {
      restoreProxy();
    }
  }

  public void startNoWait(String alias) {
    Part part = startContainer(alias);
    if (part == Part.TOXIPROXY) {
      restoreProxy();
    }
  }

  private Part startContainer(String alias) {
    Part part = part(alias);
    GenericContainer<?> container = container(part);
    try {
      if (changed.contains(part)) {
        if (container.getContainerId() != null) {
          container.stop();
          reserveIfStopped(part);
        }
        releaseReservation(part);
        container = createContainer(part);
        containers.put(part, container);
        container.start();
        changed.remove(part);
      } else if (container.getContainerId() == null) {
        releaseReservation(part);
        container.start();
      } else {
        releaseReservation(part);
        dockerClient.startContainerCmd(container.getContainerId()).exec();
      }
    } finally {
      reserveIfStopped(part);
    }
    return part;
  }

  public void restart(String alias, int stopTimeoutSeconds) {
    Part part = part(alias);
    GenericContainer<?> container = container(part);
    releaseReservation(part);
    try {
      dockerClient
          .restartContainerCmd(container.getContainerId())
          .withTimeout(stopTimeoutSeconds)
          .exec();
    } finally {
      reserveIfStopped(part);
    }
    awaitReady(alias, READY_TIMEOUT);
    if (part == Part.TOXIPROXY) {
      restoreProxy();
    }
  }

  /** Applies an environment value when the container is next started. */
  public void withEnv(String alias, String key, String value) {
    Part part = part(alias);
    GenericContainer<?> container = container(part);
    if (running(alias)) {
      throw new IllegalStateException("container must be stopped: " + alias);
    }
    env.get(part).put(Objects.requireNonNull(key), Objects.requireNonNull(value));
    if (!Objects.equals(container.getEnvMap().get(key), value)) {
      changed.add(part);
    }
  }

  public boolean running(String alias) {
    GenericContainer<?> container = container(part(alias));
    if (container.getContainerId() == null) {
      return false;
    }
    return dockerClient
        .inspectContainerCmd(container.getContainerId())
        .exec()
        .getState()
        .getRunning();
  }

  public void awaitExit(String alias, Duration timeout) {
    Part part = part(alias);
    GenericContainer<?> container = container(part);
    if (container.getContainerId() == null) {
      reserveIfStopped(part);
      return;
    }
    try {
      await()
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(50))
          .atMost(timeout)
          .until(() -> !running(alias));
      reserveIfStopped(part);
    } catch (ConditionTimeoutException e) {
      throw new IllegalStateException("container still running: " + alias, e);
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

  public String kafkaBootstrap() {
    container(Part.KAFKA);
    return "localhost:" + ports.get(Part.KAFKA);
  }

  public String extbankUrl() {
    container(Part.EXTBANK);
    return "http://localhost:" + ports.get(Part.EXTBANK);
  }

  public String extbankApiKey() {
    container(Part.EXTBANK);
    return API_KEY;
  }

  public Connection jdbc() throws SQLException {
    return DriverManager.getConnection(jdbcUrl(), "ledger_app", PASSWORD);
  }

  public Connection superuserJdbc() throws SQLException {
    return DriverManager.getConnection(jdbcUrl(), "postgres", PASSWORD);
  }

  public Connection monitorJdbc() throws SQLException {
    return DriverManager.getConnection(jdbcUrl(), "monitor_app", PASSWORD);
  }

  public void createTopic() {
    container(Part.KAFKA);
    try (AdminClient admin = adminClient()) {
      try {
        admin.createTopics(List.of(new NewTopic(MONITOR_TOPIC, 3, (short) 1))).all().get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted creating monitor topic", e);
      } catch (ExecutionException e) {
        if (!(e.getCause() instanceof TopicExistsException)) {
          throw new IllegalStateException("failed to create monitor topic", e.getCause());
        }
      }
    }
  }

  public void awaitMonitorCaughtUp(Duration timeout) {
    AtomicReference<Map<TopicPartition, String>> lagging = new AtomicReference<>(Map.of());
    try (AdminClient admin = adminClient()) {
      List<TopicPartition> partitions = monitorPartitions(admin);
      try {
        await()
            .pollDelay(Duration.ZERO)
            .pollInterval(Duration.ofMillis(100))
            .atMost(timeout)
            .until(
                () -> {
                  Map<TopicPartition, String> remaining = new LinkedHashMap<>();
                  for (MonitorOffset offset : monitorOffsets(admin, partitions)) {
                    if (offset.committed() == null || offset.committed() != offset.end()) {
                      remaining.put(
                          offset.partition(),
                          "committed="
                              + (offset.committed() == null ? "none" : offset.committed())
                              + ", end="
                              + offset.end());
                    }
                  }
                  lagging.set(Map.copyOf(remaining));
                  return remaining.isEmpty();
                });
      } catch (ConditionTimeoutException e) {
        throw new IllegalStateException(
            "monitor consumer group has lagging partitions: " + lagging.get(), e);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for monitor consumer group", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("failed to read monitor consumer offsets", e.getCause());
    }
  }

  public String monitorConsumerLag() {
    try (AdminClient admin = adminClient()) {
      List<MonitorOffset> offsets = monitorOffsets(admin, monitorPartitions(admin));
      return offsets.stream()
          .map(
              offset ->
                  MONITOR_TOPIC
                      + "-"
                      + offset.partition().partition()
                      + " committed="
                      + (offset.committed() == null ? "none" : offset.committed())
                      + " end="
                      + offset.end())
          .collect(Collectors.joining(System.lineSeparator()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for monitor consumer group", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("failed to read monitor consumer offsets", e.getCause());
    }
  }

  private static List<TopicPartition> monitorPartitions(AdminClient admin)
      throws InterruptedException, ExecutionException {
    return admin
        .describeTopics(List.of(MONITOR_TOPIC))
        .allTopicNames()
        .get()
        .get(MONITOR_TOPIC)
        .partitions()
        .stream()
        .map(partition -> new TopicPartition(MONITOR_TOPIC, partition.partition()))
        .toList();
  }

  private static List<MonitorOffset> monitorOffsets(
      AdminClient admin, List<TopicPartition> partitions)
      throws InterruptedException, ExecutionException {
    Map<TopicPartition, OffsetAndMetadata> committed =
        admin.listConsumerGroupOffsets("tally-monitor").partitionsToOffsetAndMetadata().get();
    Map<TopicPartition, OffsetSpec> offsetsRequest =
        partitions.stream()
            .collect(Collectors.toMap(partition -> partition, partition -> OffsetSpec.latest()));
    Map<TopicPartition, Long> latest =
        admin.listOffsets(offsetsRequest).all().get().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
    return partitions.stream()
        .map(
            partition -> {
              OffsetAndMetadata offset = committed.get(partition);
              return new MonitorOffset(
                  partition, offset == null ? null : offset.offset(), latest.get(partition));
            })
        .toList();
  }

  private record MonitorOffset(TopicPartition partition, Long committed, long end) {}

  public String monitorLogTail(int lines) {
    if (!running("monitor")) {
      return "monitor container is not running";
    }
    return logTail("monitor", lines);
  }

  public String logTail(String alias, int lines) {
    try {
      List<String> logLines = container(part(alias)).getLogs().lines().toList();
      int start = Math.max(0, logLines.size() - Math.max(0, lines));
      return String.join(System.lineSeparator(), logLines.subList(start, logLines.size()));
    } catch (RuntimeException e) {
      return alias + " logs unavailable";
    }
  }

  public void resetMonitor() {
    GenericContainer<?> monitor = container(Part.MONITOR);
    if (running("monitor")) {
      dockerClient.stopContainerCmd(monitor.getContainerId()).withTimeout(10).exec();
      awaitExit("monitor", READY_TIMEOUT);
    }
    try (Connection connection = superuserJdbc();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA monitor CASCADE");
      statement.execute("CREATE SCHEMA monitor AUTHORIZATION monitor_app");
      statement.execute("ALTER ROLE monitor_app SET search_path = monitor");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to reset monitor schema", e);
    }
    deleteMonitorGroup();
    start("monitor");
  }

  public List<Violation> checker() throws SQLException {
    return checker(containers.containsKey(Part.KAFKA), false);
  }

  public List<Violation> checker(boolean topicCheck, boolean requireDrained) throws SQLException {
    try (Connection connection = jdbc()) {
      InvariantChecker checker = new InvariantChecker();
      List<Violation> violations = new ArrayList<>(checker.check(connection));
      if (topicCheck) {
        container(Part.KAFKA);
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        // Keep the published set fixed before the relay can add more topic records.
        new OutboxStore().publishedEventIds(connection);
        TopicCheck.Snapshot snapshot =
            TopicCheck.read(kafkaBootstrap(), "tally.transactions.v1", Duration.ofSeconds(30));
        violations.addAll(checker.checkTopic(connection, snapshot, requireDrained));
        connection.commit();
      }
      return List.copyOf(violations);
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
    limits.put("kafka", KAFKA_MEMORY_MB);
    limits.put("extbank", EXTBANK_MEMORY_MB);
    limits.put("worker", WORKER_MEMORY_MB);
    limits.put("worker2", WORKER_MEMORY_MB);
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
          .pollInterval(Duration.ofMillis(50))
          .atMost(timeout)
          .until(
              () ->
                  switch (part) {
                    case POSTGRES -> postgresReady();
                    case TOXIPROXY ->
                        httpReady("http://localhost:" + ports.get(Part.TOXIPROXY) + "/version");
                    case LEDGER -> httpReady(ledgerUrl() + "/ready");
                    case KAFKA -> true;
                    case MONITOR ->
                        httpReady("http://localhost:" + ports.get(Part.MONITOR) + "/ready");
                    case EXTBANK -> httpReady(extbankUrl() + "/ready");
                    case WORKER, WORKER2 -> httpReady(workerUrl(part) + "/health");
                  });
    } catch (ConditionTimeoutException e) {
      throw new IllegalStateException("container not ready: " + alias, e);
    }
  }

  private void awaitWorkerReadyOrHalt(String alias, Duration timeout) {
    Part part = part(alias);
    GenericContainer<?> container = container(part);
    try {
      await()
          .pollDelay(Duration.ZERO)
          .pollInterval(Duration.ofMillis(50))
          .atMost(timeout)
          .until(
              () -> {
                if (httpReady(workerUrl(part) + "/health")) {
                  return true;
                }
                var state =
                    dockerClient.inspectContainerCmd(container.getContainerId()).exec().getState();
                if (state.getRunning()) {
                  return false;
                }
                reserveIfStopped(part);
                Long exitCode = state.getExitCodeLong();
                if (exitCode != null
                    && exitCode == 137L
                    && !env.get(part).getOrDefault("TALLY_FAILPOINT", "").isEmpty()) {
                  return true;
                }
                throw new IllegalStateException(
                    "worker exited before becoming ready: "
                        + alias
                        + " (exit code "
                        + exitCode
                        + ")");
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
      if (container != null && container.getContainerId() != null) {
        cleanup.add(container::stop);
      }
    }
    cleanup.add(network::close);
    cleanup.add(issuer::close);
    cleanup.add(http::close);
    cleanup.add(this::closeReservations);
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
              new GenericContainer<>(LEDGER_IMAGE).withExposedPorts(8080).waitingFor(NO_WAIT);
          case KAFKA ->
              new GenericContainer<>(KAFKA_IMAGE)
                  .withExposedPorts(29092)
                  .waitingFor(
                      Wait.forLogMessage(".*Kafka Server started.*", 1)
                          .withStartupTimeout(READY_TIMEOUT));
          case MONITOR ->
              new GenericContainer<>(MONITOR_IMAGE)
                  .withExposedPorts(8082)
                  .waitingFor(
                      Wait.forHttp("/ready").forStatusCode(200).withStartupTimeout(READY_TIMEOUT));
          case EXTBANK ->
              new GenericContainer<>(EXTBANK_IMAGE)
                  .withExposedPorts(8083)
                  .waitingFor(
                      Wait.forHttp("/ready").forStatusCode(200).withStartupTimeout(READY_TIMEOUT));
          case WORKER, WORKER2 ->
              new GenericContainer<>(LEDGER_IMAGE).withExposedPorts(8084).waitingFor(NO_WAIT);
        };
    int containerPort =
        switch (part) {
          case POSTGRES -> 5432;
          case TOXIPROXY -> 8474;
          case LEDGER -> 8080;
          case KAFKA -> 29092;
          case MONITOR -> 8082;
          case EXTBANK -> 8083;
          case WORKER, WORKER2 -> 8084;
        };
    long memoryMb =
        switch (part) {
          case POSTGRES -> POSTGRES_MEMORY_MB;
          case TOXIPROXY -> TOXIPROXY_MEMORY_MB;
          case LEDGER -> LEDGER_MEMORY_MB;
          case KAFKA -> KAFKA_MEMORY_MB;
          case MONITOR -> MONITOR_MEMORY_MB;
          case EXTBANK -> EXTBANK_MEMORY_MB;
          case WORKER, WORKER2 -> WORKER_MEMORY_MB;
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

  private AdminClient adminClient() {
    return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap()));
  }

  private void deleteMonitorGroup() {
    try (AdminClient admin = adminClient()) {
      try {
        admin.deleteConsumerGroups(List.of("tally-monitor")).all().get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted deleting monitor consumer group", e);
      } catch (ExecutionException e) {
        if (!(e.getCause() instanceof GroupIdNotFoundException)) {
          throw new IllegalStateException("failed to delete monitor consumer group", e.getCause());
        }
      }
    }
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
      case "kafka" -> Part.KAFKA;
      case "monitor" -> Part.MONITOR;
      case "extbank" -> Part.EXTBANK;
      case "worker" -> Part.WORKER;
      case "worker2" -> Part.WORKER2;
      default -> throw new IllegalArgumentException("unknown container alias: " + alias);
    };
  }

  private static String alias(Part part) {
    return part.name().toLowerCase(Locale.ROOT);
  }

  private static Map<Part, ServerSocket> reservePorts() {
    Map<Part, ServerSocket> reservations = new EnumMap<>(Part.class);
    try {
      for (Part part : Part.values()) {
        reservations.put(part, new ServerSocket(0));
      }
      return reservations;
    } catch (IOException e) {
      for (ServerSocket reservation : reservations.values()) {
        try {
          reservation.close();
        } catch (IOException closeFailure) {
          e.addSuppressed(closeFailure);
        }
      }
      throw new UncheckedIOException("failed to reserve host ports", e);
    }
  }

  private static Map<Part, Integer> portMap(Map<Part, ServerSocket> reservations) {
    Map<Part, Integer> ports = new EnumMap<>(Part.class);
    reservations.forEach((part, reservation) -> ports.put(part, reservation.getLocalPort()));
    return ports;
  }

  private void releaseReservation(Part part) {
    ServerSocket reservation = reservations.remove(part);
    if (reservation != null) {
      try {
        reservation.close();
      } catch (IOException e) {
        throw new UncheckedIOException("failed to release host port for " + alias(part), e);
      }
    }
  }

  private void reserveIfStopped(Part part) {
    GenericContainer<?> container = containers.get(part);
    if (reservations.containsKey(part)
        || (container != null
            && container.getContainerId() != null
            && dockerClient
                .inspectContainerCmd(container.getContainerId())
                .exec()
                .getState()
                .getRunning())) {
      return;
    }
    try {
      reservations.put(part, new ServerSocket(ports.get(part)));
    } catch (IOException e) {
      LOGGER.log(
          Level.WARNING,
          "Could not reserve host port " + ports.get(part) + " for " + alias(part),
          e);
    }
  }

  private void closeReservations() {
    RuntimeException failure = null;
    for (Part part : Part.values()) {
      ServerSocket reservation = reservations.remove(part);
      if (reservation != null) {
        try {
          reservation.close();
        } catch (IOException e) {
          if (failure == null) {
            failure = new UncheckedIOException("failed to close host port reservations", e);
          } else {
            failure.addSuppressed(e);
          }
        }
      }
    }
    if (failure != null) {
      throw failure;
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

  private String workerUrl(Part part) {
    return "http://localhost:" + ports.get(part);
  }

  private static void requireImage(DockerClient dockerClient, String image) {
    try {
      dockerClient.inspectImageCmd(image).exec();
    } catch (NotFoundException e) {
      throw new IllegalStateException("image " + image + " not found, run: sh task.sh images", e);
    }
  }
}
