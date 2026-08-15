package pn.torn.goldeneye.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.RejectedExecutionException;

/**
 * 股票调度资源配置 - 为实时采集、VIP 15m 轮次与 Tornsy 回填分配隔离的执行资源
 * <p>
 * 实时分钟采集(最高优先级)与 VIP 轮次各使用独立单线程 {@link ThreadPoolTaskScheduler}，
 * 通过 {@code @Scheduled(scheduler = "...")} 按调度器 Bean 名绑定，避免重型 15m
 * bar/feature 重建占用默认调度线程导致实时采集延后。Tornsy 人工回填与每日巡检回填使用
 * 受限并发 {@link ThreadPoolTaskExecutor}（单并发 + 队列1），拒绝时记录 WARN 并抛出
 * {@link RejectedExecutionException} 使调用方可感知，不得 {@code CallerRuns} 回退到调度线程。
 * <p>
 * 默认 {@code taskScheduler} 保持 Spring Boot 默认单线程语义，继续服务未显式指定
 * 调度器的其余 {@code @Scheduled} 任务（非实时、非 VIP、非回填入口）。
 *
 * @author Bai
 * @version 1.2.18
 * @since 2026.08.14
 */
@Slf4j
@Configuration
public class StockSchedulingConfiguration {

    /**
     * 实时分钟采集专用调度器（单线程、独立，禁止被 15m 重任务占用）。
     *
     * @return 实时采集调度器 Bean
     */
    @Bean
    public ThreadPoolTaskScheduler realtimeStockScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stock-realtime-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(0);
        return scheduler;
    }

    /**
     * VIP 15m 轮次专用调度器（单线程、独立，bar/feature/轮次事务在本调度器内串行处理）。
     *
     * @return VIP 轮次调度器 Bean
     */
    @Bean
    public ThreadPoolTaskScheduler vipStockRoundScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stock-vip-round-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /**
     * 默认 {@code @Scheduled} 触发器调度器（与 Spring Boot 默认一致的单线程轻量触发池），
     * 服务未指定 {@code scheduler} 的其余定时任务；实时与 VIP 不使用本调度器。
     *
     * @return 默认调度器 Bean（名为 taskScheduler 供 {@link org.springframework.scheduling.config.TaskSchedulerRouter} 解析）
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /**
     * Tornsy 历史回填专用执行器（单并发 + 队列容量1）。
     * <p>
     * 回填的 HTTP 拉取、分钟事实插入与派生数据重建在本执行器内串行执行，与实时采集
     * 和 VIP 轮次完全隔离。执行器已满时记录 WARN 并抛出 {@link RejectedExecutionException}，
     * 使提交方（人工指令/每日巡检）能感知投递失败并释放防重入标记；禁止
     * {@code CallerRuns} 让回填占用调度线程。
     *
     * @return 回填执行器 Bean
     */
    @Bean
    public ThreadPoolTaskExecutor stockBackfillExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("stock-backfill-");
        executor.setRejectedExecutionHandler((task, executorRef) -> {
            log.warn("股票回填执行器已满, 拒绝新的回填触发, task={}", task);
            throw new RejectedExecutionException("股票回填执行器已满: " + task);
        });
        return executor;
    }
}
