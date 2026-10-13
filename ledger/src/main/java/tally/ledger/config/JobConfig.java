package tally.ledger.config;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

@Configuration(proxyBeanMethods = false)
public class JobConfig {

  private static final Logger log = LoggerFactory.getLogger(JobConfig.class);

  private final LedgerProperties properties;
  private final ObjectProvider<Job> jobs;
  private final List<Thread> threads = new ArrayList<>();

  public JobConfig(LedgerProperties properties, ObjectProvider<Job> jobs) {
    this.properties = properties;
    this.jobs = jobs;
  }

  @EventListener
  public void startJobs(ApplicationReadyEvent event) {
    for (Job job : jobs.orderedStream().toList()) {
      if (properties.has(job.role())) {
        Thread thread = new Thread(() -> run(job), "job-" + job.name());
        thread.setDaemon(true);
        thread.start();
        threads.add(thread);
        log.info("job started: {}", job.name());
      }
    }
  }

  @EventListener
  public void stopJobs(ContextClosedEvent event) {
    threads.forEach(Thread::interrupt);
    for (Thread thread : threads) {
      try {
        thread.join(5000);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static void run(Job job) {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        job.runOnce();
      } catch (Exception exception) {
        log.error("job failed: {}", job.name(), exception);
      }
      try {
        Thread.sleep(job.pollMs());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public interface Job {
    String name();

    String role();

    long pollMs();

    int runOnce();
  }
}
