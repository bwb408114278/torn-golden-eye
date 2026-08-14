package pn.torn.goldeneye.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票调度资源配置单元测试 - 验证实时/VIP/回填执行资源隔离配置。
 * <p>
 * 验证 {@link StockSchedulingConfiguration} 定义的实时采集与 VIP 轮次调度器为不同对象、
 * 线程名前缀与容量符合设计;回填执行器受限并发且拒绝策略不得使用
 * {@link ThreadPoolExecutor.CallerRunsPolicy}（避免回填回退占用调度线程）。
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.08.14
 */
@DisplayName("股票调度资源配置测试")
class StockSchedulingConfigurationTest {

    /**
     * 被测试配置实例（无构造依赖,可直接实例化）
     */
    private final StockSchedulingConfiguration configuration = new StockSchedulingConfiguration();

    @Test
    @DisplayName("调度器隔离_实时与VIP为不同对象且线程名前缀正确")
    void schedulers_realtimeAndVipAreDistinctWithCorrectPrefix() {
        ThreadPoolTaskScheduler realtime = configuration.realtimeStockScheduler();
        ThreadPoolTaskScheduler vip = configuration.vipStockRoundScheduler();

        assertNotSame(realtime, vip, "实时采集与VIP轮次必须使用不同调度器");
        assertEquals(1, realtime.getPoolSize(), "实时调度器应为单线程");
        assertEquals("stock-realtime-scheduler-", realtime.getThreadNamePrefix(), "实时调度器线程名前缀错误");
        assertEquals(1, vip.getPoolSize(), "VIP调度器应为单线程");
        assertEquals("stock-vip-round-scheduler-", vip.getThreadNamePrefix(), "VIP调度器线程名前缀错误");
    }

    @Test
    @DisplayName("回填执行器_受限单并发且拒绝策略不得CallerRuns")
    void backfillExecutor_limitedConcurrencyAndNonCallerRunsRejection() {
        ThreadPoolTaskExecutor backfill = configuration.stockBackfillExecutor();
        backfill.initialize();

        assertEquals(1, backfill.getCorePoolSize(), "回填执行器核心线程应为1");
        assertEquals(1, backfill.getMaxPoolSize(), "回填执行器最大线程应为1");
        assertEquals(1, backfill.getQueueCapacity(), "回填执行器队列容量应为1");
        assertEquals("stock-backfill-", backfill.getThreadNamePrefix(), "回填执行器线程名前缀错误");
        assertFalse(backfill.getThreadPoolExecutor().getRejectedExecutionHandler()
                        instanceof ThreadPoolExecutor.CallerRunsPolicy,
                "回填执行器拒绝策略禁止CallerRuns,避免回填占用调度线程");
    }
}
