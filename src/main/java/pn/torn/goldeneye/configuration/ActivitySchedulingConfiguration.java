package pn.torn.goldeneye.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 活跃度采集调度资源配置
 * <p>
 * 活跃度采集在单次调度内串行处理多批帮派（每批50个），单批耗时最坏可到分钟级，
 * 挂在默认单线程调度器上会把日报、巡检、VIP订阅与通知一起堵住。本配置为它分配独立单线程调度器，
 * 通过 {@code @Scheduled(scheduler = "activityCollectScheduler")} 绑定；
 * 默认 {@code taskScheduler} 的语义不变，继续服务其余未指定调度器的定时任务。
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.19
 */
@Configuration
public class ActivitySchedulingConfiguration {

    /**
     * 活跃度采集专用调度器（单线程、独立，采集批次在本调度器内串行执行）。
     *
     * @return 活跃度采集调度器 Bean
     */
    @Bean
    public ThreadPoolTaskScheduler activityCollectScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("activity-collect-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(0);
        return scheduler;
    }
}
