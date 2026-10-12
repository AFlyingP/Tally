package tally.proof.eval;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tally.proof.fault.FaultStack;

public final class MachineInfo {

  private static final long BYTES_PER_MEGABYTE = 1_048_576L;
  private static final Path CPU_INFO = Path.of("/proc/cpuinfo");

  private MachineInfo() {}

  public static Map<String, Object> collect() {
    LinkedHashMap<String, Object> machine = new LinkedHashMap<>();
    machine.put("cpu_model", cpuModel());
    machine.put("cpu_cores", Runtime.getRuntime().availableProcessors());
    machine.put("memory_mb", memoryMb());
    machine.put("docker_memory_mb", dockerMemoryMb());
    machine.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
    machine.put("container_limits_mb", FaultStack.containerLimitsMb());
    return machine;
  }

  private static String cpuModel() {
    String processorIdentifier = System.getenv("PROCESSOR_IDENTIFIER");
    if (processorIdentifier != null) {
      return processorIdentifier;
    }
    if (Files.exists(CPU_INFO)) {
      try {
        for (String line : Files.readAllLines(CPU_INFO, StandardCharsets.UTF_8)) {
          int colon = line.indexOf(':');
          if (colon >= 0 && line.substring(0, colon).trim().equals("model name")) {
            return line.substring(colon + 1).trim();
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException("failed to read cpu information", e);
      }
    }
    return System.getProperty("os.arch");
  }

  private static long memoryMb() {
    java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
    if (bean instanceof OperatingSystemMXBean operatingSystem) {
      return operatingSystem.getTotalMemorySize() / BYTES_PER_MEGABYTE;
    }
    return -1;
  }

  private static long dockerMemoryMb() {
    Process process;
    try {
      process =
          new ProcessBuilder("docker", "info", "--format", "{{.MemTotal}}")
              .redirectErrorStream(true)
              .start();
    } catch (IOException e) {
      return -1;
    }
    try (InputStream outputStream = process.getInputStream()) {
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return -1;
      }
      if (process.exitValue() != 0) {
        return -1;
      }
      String output = new String(outputStream.readAllBytes(), StandardCharsets.UTF_8);
      try {
        return Long.parseLong(output.trim()) / BYTES_PER_MEGABYTE;
      } catch (NumberFormatException e) {
        return -1;
      }
    } catch (IOException e) {
      return -1;
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      return -1;
    }
  }
}
