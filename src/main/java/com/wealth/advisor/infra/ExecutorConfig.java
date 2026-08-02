package com.wealth.advisor.infra;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A dedicated executor for fanning out several independent, blocking calls concurrently — see
 * {@code Optimization.md} P1-5 for why this exists: {@code PortfolioAllocationTool} used to wrap
 * its per-ticker price lookups in {@code CompletableFuture.supplyAsync(...)} with no explicit
 * executor, meaning it silently ran on the JVM-wide {@code ForkJoinPool.commonPool()} — shared with
 * anything else in the process that reaches for parallel streams or the common pool, and sized by
 * {@code availableProcessors() - 1}, which is minimal on this app's 1-vCPU Cloud Run instance.
 *
 * <p>A virtual-thread-per-task executor (Java 21+) is the right fit for this specific workload
 * shape — a handful of tasks that each just block waiting on network I/O (each price lookup
 * ultimately waits on 4 FMP HTTP calls plus a Firestore round trip) — rather than a fixed-size
 * platform-thread pool: there's no pool size to size or tune (a virtual thread is cheap enough to
 * hand out one per task), and blocking one doesn't tie up an OS thread the way a platform thread
 * pool's would. {@code ExecutorService} has implemented {@link AutoCloseable} since Java 19, so
 * Spring's default bean-destruction convention (looks for a public {@code close()}/{@code
 * shutdown()} method) shuts this down automatically on context close — no explicit {@code
 * destroyMethod} needed.
 */
@Configuration
public class ExecutorConfig {

  @Bean
  public ExecutorService priceLookupExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
