package pn.torn.goldeneye.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 动态定时任务配置
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.30
 */
@Service
@RequiredArgsConstructor
public class DynamicTaskService {
    private final ThreadPoolTaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * 创建/更新一次性定时任务
     *
     * @param taskId        任务唯一ID
     * @param task          要执行的任务逻辑
     * @param executionTime 任务执行时间(UTC时间)
     * @param callback      可选回调(执行后通知)
     */
    public void updateTask(String taskId, Runnable task, Instant executionTime, TaskCallback callback) {
        // 取消现有的同ID任务
        cancelTask(taskId);
        // 计算延迟时间
        long delayMillis = Math.max(0, executionTime.toEpochMilli() - System.currentTimeMillis());

        Runnable wrappedTask = () -> {
            try {
                task.run();
                if (callback != null) {
                    callback.onTaskExecuted(taskId, true);
                }
            } finally {
                scheduledTasks.remove(taskId);
            }
        };

        // 提交任务
        ScheduledFuture<?> future = taskScheduler.schedule(
                wrappedTask,
                Instant.ofEpochMilli(System.currentTimeMillis() + delayMillis));
        scheduledTasks.put(taskId, future);
    }

    /**
     * 提前取消任务
     *
     * @param taskId 任务ID
     * @return true-取消成功, false-任务不存在或已完成
     */
    public boolean cancelTask(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.get(taskId);
        if (future != null) {
            boolean canceled = future.cancel(false);
            scheduledTasks.remove(taskId);
            return canceled;
        }
        return false;
    }

    /**
     * 检查任务状态
     *
     * @param taskId 任务ID
     * @return 任务状态字符串
     */
    public String checkTaskStatus(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.get(taskId);
        if (future == null) return "NOT_EXISTS";
        if (future.isDone()) return "COMPLETED";
        if (future.isCancelled()) return "CANCELLED";
        return "SCHEDULED";
    }

    /**
     * 更新任务时间
     *
     * @param taskId  任务ID
     * @param newTime 新的执行时间
     * @return true-更新成功, false-任务不存在
     */
    public boolean updateTaskTime(String taskId, Instant newTime, TaskCallback callback) {
        if (!scheduledTasks.containsKey(taskId)) return false;
        cancelTask(taskId);

        // 重新创建任务(复用原任务逻辑)
        Runnable originalTask = () -> {
        }; // 实际应用中应从上下文获取
        updateTask(taskId, originalTask, newTime, callback);
        return true;
    }

    public interface TaskCallback {
        /**
         * 任务是否执行完毕
         *
         * @param taskId   任务ID
         * @param executed 是否执行完毕
         */
        void onTaskExecuted(String taskId, boolean executed);
    }
}