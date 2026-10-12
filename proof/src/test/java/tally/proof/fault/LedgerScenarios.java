package tally.proof.fault;

import static org.assertj.core.api.Assertions.assertThat;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import tally.proof.harness.Sizes;

public final class LedgerScenarios {

  private LedgerScenarios() {}

  public static FaultResult apiKill(FaultStack stack, String strategy, Sizes sizes, long seed) {
    LoadDriver driver = driver(stack, sizes, 2000, seed);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      driver.setup();
      driver.start();
      long started = System.nanoTime();
      Future<?> fault =
          schedule(
              scheduler,
              () -> {
                stack.kill("ledger");
                stack.start("ledger");
                return null;
              },
              started,
              delayMillis(sizes, 40));
      awaitEnd(driver, fault);
      recover(stack, driver);
      FaultResult result = FaultResult.collect(Scenario.API_KILL, strategy, stack, driver);
      assertThat(driver.failures()).as("api kill driver failures").isEmpty();
      assertThat(driver.resentOperations())
          .as("api kill resent operations")
          .isGreaterThanOrEqualTo(1);
      return result;
    } finally {
      shutdown(scheduler);
    }
  }

  public static FaultResult connectionCut(
      FaultStack stack, String strategy, Sizes sizes, long seed) {
    LoadDriver driver = driver(stack, sizes, 2000, seed);
    Proxy proxy = stack.toxiproxy();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      driver.setup();
      driver.start();
      long started = System.nanoTime();
      long faultMillis = delayMillis(sizes, 40);
      Future<?> fault =
          schedule(
              scheduler,
              () -> proxy.toxics().resetPeer("cut", ToxicDirection.DOWNSTREAM, 0),
              started,
              faultMillis);
      Future<?> heal =
          schedule(
              scheduler,
              () -> {
                removeToxic(proxy, "cut");
                return null;
              },
              started,
              faultMillis + 3000);
      awaitEnd(driver, fault, heal);
      recover(stack, driver);
      FaultResult result = FaultResult.collect(Scenario.CONNECTION_CUT, strategy, stack, driver);
      assertThat(driver.failures()).as("connection cut driver failures").isEmpty();
      return result;
    } finally {
      shutdown(scheduler);
      removeToxic(proxy, "cut");
    }
  }

  public static FaultResult slowDatabase(
      FaultStack stack, String strategy, Sizes sizes, long seed) {
    LoadDriver driver = driver(stack, sizes, 1000, seed);
    Proxy proxy = stack.toxiproxy();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      driver.setup();
      driver.start();
      long started = System.nanoTime();
      Future<?> fault =
          schedule(
              scheduler,
              () -> proxy.toxics().latency("slow", ToxicDirection.DOWNSTREAM, 3000),
              started,
              delayMillis(sizes, 20));
      Future<?> heal =
          schedule(
              scheduler,
              () -> {
                removeToxic(proxy, "slow");
                return null;
              },
              started,
              delayMillis(sizes, 60));
      awaitEnd(driver, fault, heal);
      recover(stack, driver);
      FaultResult result = FaultResult.collect(Scenario.SLOW_DATABASE, strategy, stack, driver);
      assertThat(driver.failures()).as("slow database driver failures").isEmpty();
      return result;
    } finally {
      shutdown(scheduler);
      removeToxic(proxy, "slow");
    }
  }

  public static FaultResult databaseRestart(
      FaultStack stack, String strategy, Sizes sizes, long seed) {
    LoadDriver driver = driver(stack, sizes, 2000, seed);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      driver.setup();
      driver.start();
      long started = System.nanoTime();
      Future<?> fault =
          schedule(
              scheduler,
              () -> {
                stack.restart("postgres", 10);
                return null;
              },
              started,
              delayMillis(sizes, 40));
      awaitEnd(driver, fault);
      recover(stack, driver);
      FaultResult result = FaultResult.collect(Scenario.DATABASE_RESTART, strategy, stack, driver);
      assertThat(driver.failures()).as("database restart driver failures").isEmpty();
      return result;
    } finally {
      shutdown(scheduler);
    }
  }

  public static FaultResult sameKeyBurst(
      FaultStack stack, String strategy, Sizes sizes, long seed) {
    LoadDriver driver = driver(stack, sizes, 2000, seed);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      driver.setup();
      driver.sameKeyBurstEvery(20);
      driver.start();
      awaitEnd(driver);
      recover(stack, driver);
      FaultResult result = FaultResult.collect(Scenario.SAME_KEY_BURST, strategy, stack, driver);
      assertThat(driver.failures()).as("same key burst driver failures").isEmpty();
      assertThat(driver.bursts()).as("same key bursts").isGreaterThanOrEqualTo(1);
      return result;
    } finally {
      shutdown(scheduler);
    }
  }

  private static LoadDriver driver(FaultStack stack, Sizes sizes, long timeoutMillis, long seed) {
    return new LoadDriver(
        stack,
        UUID.randomUUID().toString(),
        sizes.faultThreads(),
        Duration.ofSeconds(sizes.faultSeconds()),
        Duration.ofMillis(timeoutMillis),
        seed);
  }

  private static long delayMillis(Sizes sizes, int percent) {
    return Duration.ofSeconds(sizes.faultSeconds()).toMillis() * percent / 100;
  }

  private static Future<?> schedule(
      ScheduledExecutorService scheduler, Callable<?> task, long started, long delayMillis) {
    long remaining = TimeUnit.MILLISECONDS.toNanos(delayMillis) - (System.nanoTime() - started);
    return scheduler.schedule(task, remaining, TimeUnit.NANOSECONDS);
  }

  private static void awaitEnd(LoadDriver driver, Future<?>... tasks) {
    driver.awaitEnd();
    try {
      for (Future<?> task : tasks) {
        task.get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fault task interrupted", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("fault task failed", e.getCause());
    }
  }

  private static void recover(FaultStack stack, LoadDriver driver) {
    stack.awaitReady("ledger", Duration.ofSeconds(120));
    driver.resolveUnresolved(Duration.ofSeconds(60));
  }

  private static void shutdown(ScheduledExecutorService scheduler) {
    scheduler.shutdownNow();
    scheduler.close();
  }

  private static void removeToxic(Proxy proxy, String name) {
    try {
      for (Toxic toxic : proxy.toxics().getAll()) {
        if (toxic.getName().equals(name)) {
          toxic.remove();
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to remove database toxic", e);
    }
  }
}
