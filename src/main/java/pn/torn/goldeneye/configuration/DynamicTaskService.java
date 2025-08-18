package pn.torn.goldeneye.configuration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.utils.DateTimeUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 动态定时任务配置
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.30
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicTaskService {
    private final ThreadPoolTaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public DynamicTaskService() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("dynamic-task-");
        scheduler.initialize();
        this.taskScheduler = scheduler;
    }

    /**
     * 创建/更新一次性定时任务
     *
     * @param taskId   任务唯一ID
     * @param task     要执行的任务逻辑
     * @param execTime 任务执行时间(UTC时间)
     */
    public void updateTask(String taskId, Runnable task, LocalDateTime execTime) {
        updateTask(taskId, task, execTime, null);
    }

    /**
     * 创建/更新一次性定时任务
     *
     * @param taskId   任务唯一ID
     * @param task     要执行的任务逻辑
     * @param execTime 任务执行时间
     * @param callback 可选回调(执行后通知)
     */
    public void updateTask(String taskId, Runnable task, LocalDateTime execTime, TaskCallback callback) {
        // 取消现有的同ID任务
        cancelTask(taskId);
        // 计算延迟时间
        long delayMillis = Math.max(0, DateTimeUtils.convertToInstant(execTime).toEpochMilli() - System.currentTimeMillis());
        // 创建 TaskWrapper 实例（持有任务逻辑和回调）
        TaskWrapper wrapper = new TaskWrapper(taskId, task, callback);
        // 提交任务并获取 ScheduledFuture
        ScheduledFuture<?> future = taskScheduler.schedule(
                wrapper,
                Instant.ofEpochMilli(System.currentTimeMillis() + delayMillis));
        // 设置 future 引用到 wrapper 中
        wrapper.setFuture(future);
        // 存储到 scheduledTasks
        scheduledTasks.put(taskId, future);
        log.debug("添加定时任务成功, id: " + taskId + " 执行时间: " + DateTimeUtils.convertToString(execTime));
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
            log.debug("取消定时任务成功, id: " + taskId);
            return canceled;
        }
        return false;
    }

    /**
     * 获取排序中的任务
     *
     * @return Key为任务名，Value为执行时间
     */
    public Map<String, LocalDateTime> getScheduledTask() {
        Map<String, LocalDateTime> resultMap = HashMap.newHashMap(scheduledTasks.size());
        LocalDateTime current = LocalDateTime.now();
        for (Map.Entry<String, ScheduledFuture<?>> entry : scheduledTasks.entrySet()) {
            ScheduledFuture<?> future = entry.getValue();
            if (future.isDone() || future.isCancelled()) {
                continue;
            }

            long delayMillis = future.getDelay(TimeUnit.MILLISECONDS);
            if (delayMillis > 0) {
                resultMap.put(entry.getKey(), current.plusSeconds(delayMillis / 1000).plusSeconds(1));
            }
        }

        return resultMap;
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

    /**
     * 内部任务包装类，用于持有 future 引用
     */
    private class TaskWrapper implements Runnable {
        private final String taskId;
        private final Runnable task;
        private final TaskCallback callback;
        private final AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        public TaskWrapper(String taskId, Runnable task, TaskCallback callback) {
            this.taskId = taskId;
            this.task = task;
            this.callback = callback;
        }

        public void setFuture(ScheduledFuture<?> future) {
            this.futureRef.set(future);
        }

        @Override
        public void run() {
            try {
                task.run();
                if (callback != null) {
                    callback.onTaskExecuted(taskId, true);
                }
                log.debug("定时任务执行完毕成功, id: " + taskId);
            } finally {
                ScheduledFuture<?> currentFuture = futureRef.get();
                if (currentFuture != null) {
                    scheduledTasks.remove(taskId, currentFuture);
                }
            }
        }
    }
}