package pn.torn.goldeneye.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisConfigTest {

    @Test
    @DisplayName("活跃度采集执行器应使用虚拟线程并允许任务并发执行")
    void shouldExecuteActivityCollectionTasksConcurrently() throws Exception {
        SimpleAsyncTaskExecutor executor = new RedisConfig().activityCollectExecutor();
        int taskCount = 20;
        CountDownLatch started = new CountDownLatch(taskCount);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicBoolean allVirtual = new AtomicBoolean(true);
        List<CompletableFuture<Void>> futures = new ArrayList<>(taskCount);

        try {
            for (int i = 0; i < taskCount; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    allVirtual.compareAndSet(true, Thread.currentThread().isVirtual());
                    int current = running.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        running.decrementAndGet();
                    }
                }, executor));
            }

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(taskCount, peak.get());
            assertTrue(allVirtual.get());
        } finally {
            release.countDown();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            executor.close();
        }
    }
}
