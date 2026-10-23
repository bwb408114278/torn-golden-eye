package pn.torn.goldeneye.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * 活跃度采集调度资源配置单元测试 - 验证采集批次在独立单线程调度器上执行。
 * <p>
 * 验证 {@link ActivitySchedulingConfiguration#activityCollectScheduler()}：单线程、线程名前缀固定、
 * 与默认调度器不是同一实例，避免采集批次占用默认调度器线程而阻塞其它定时任务。
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.19
 */
@DisplayName("活跃度采集调度资源配置测试")
class ActivitySchedulingConfigurationTest {

    /**
     * 被测试配置实例（无构造依赖,可直接实例化）
     */
    private final ActivitySchedulingConfiguration configuration = new ActivitySchedulingConfiguration();

    @Test
    @DisplayName("采集调度器_单线程独立且线程名前缀正确")
    void activityCollectScheduler_singleThreadAndIndependent() {
        ThreadPoolTaskScheduler scheduler = configuration.activityCollectScheduler();
        ThreadPoolTaskScheduler defaultScheduler = new StockSchedulingConfiguration().taskScheduler();

        assertNotSame(scheduler, defaultScheduler, "活跃度采集不得复用默认调度器");
        assertEquals(1, scheduler.getPoolSize(), "采集调度器应为单线程");
        assertEquals("activity-collect-scheduler-", scheduler.getThreadNamePrefix(), "采集调度器线程名前缀错误");
    }
}
