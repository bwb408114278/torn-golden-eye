package pn.torn.goldeneye.configuration.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * 应用启动恢复任务投递门面。
 *
 * <p>本类只负责复用现有虚拟线程执行器投递任务、记录生命周期日志和处理投递拒绝，
 * 不承载任何业务状态推进或远程调用规则。</p>
 *
 * @author Bai
 * @version 1.4.5
 * @since 2026.08.25
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartupRecoveryDispatcher {
    private final ThreadPoolTaskExecutor virtualThreadExecutor;

    /**
     * 异步投递一个启动恢复任务。
     *
     * @param task 启动恢复任务参数
     */
    public void submit(StartupRecoveryTask task) {
        Objects.requireNonNull(task, "task");
        log.info("启动恢复任务已受理, taskName={}", task.taskName());
        try {
            virtualThreadExecutor.execute(() -> execute(task));
        } catch (RejectedExecutionException exception) {
            log.warn("启动恢复任务投递被拒绝, taskName={}", task.taskName(), exception);
            try {
                task.rejectedAction().run();
            } catch (Exception rejectedActionException) {
                log.error("启动恢复任务拒绝回调执行失败, taskName={}", task.taskName(), rejectedActionException);
            }
        }
    }

    /**
     * 执行已投递的启动恢复任务并隔离任务异常。
     *
     * @param task 启动恢复任务参数
     */
    private void execute(StartupRecoveryTask task) {
        long startedAt = System.nanoTime();
        log.info("启动恢复任务开始, taskName={}", task.taskName());
        try {
            task.runnable().run();
            log.info("启动恢复任务完成, taskName={}, durationMs={}", task.taskName(), elapsedMillis(startedAt));
        } catch (Exception exception) {
            log.error("启动恢复任务执行失败, taskName={}, durationMs={}",
                    task.taskName(), elapsedMillis(startedAt), exception);
        }
    }

    /**
     * 计算任务从指定纳秒时间点开始经过的毫秒数。
     *
     * @param startedAt 任务开始时的纳秒时间点
     * @return 已经过的毫秒数
     */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /**
     * 启动恢复任务参数。
     *
     * @param taskName       稳定的任务诊断名称，不得包含敏感信息
     * @param runnable       实际恢复命令
     * @param rejectedAction 投递被拒绝时的业务补偿动作
     */
    public record StartupRecoveryTask(String taskName, Runnable runnable, Runnable rejectedAction) {
        public StartupRecoveryTask {
            Objects.requireNonNull(taskName, "taskName");
            Objects.requireNonNull(runnable, "runnable");
            Objects.requireNonNull(rejectedAction, "rejectedAction");
        }
    }
}
