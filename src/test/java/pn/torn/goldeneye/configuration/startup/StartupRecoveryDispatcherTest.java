package pn.torn.goldeneye.configuration.startup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 启动恢复任务投递门面测试。
 *
 * @author Bai
 * @version 1.4.5
 * @since 2026.08.25
 */
@DisplayName("启动恢复任务投递门面测试")
class StartupRecoveryDispatcherTest {

    @Test
    @DisplayName("启动恢复命令受理后只投递不等待")
    void submit_shouldOnlyDispatchUntilRunnableIsExecuted() {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean accepted = new AtomicBoolean();
        doAnswer(invocation -> {
            accepted.set(true);
            return null;
        }).when(executor).execute(any(Runnable.class));

        StartupRecoveryDispatcher dispatcher = new StartupRecoveryDispatcher(executor);
        dispatcher.submit(new StartupRecoveryDispatcher.StartupRecoveryTask(
                "test-recovery", () -> executed.set(true), () -> { }));

        assertTrue(accepted.get());
        assertFalse(executed.get());

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(runnableCaptor.capture());
        runnableCaptor.getValue().run();
        assertTrue(executed.get());
    }

    @Test
    @DisplayName("启动恢复任务投递被拒绝时执行拒绝回调且不回退业务任务")
    void submit_whenRejected_shouldInvokeRejectedActionWithoutRunningTask() {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        AtomicBoolean executed = new AtomicBoolean();
        AtomicBoolean rejected = new AtomicBoolean();
        doThrow(new RejectedExecutionException("test rejected"))
                .when(executor).execute(any(Runnable.class));

        StartupRecoveryDispatcher dispatcher = new StartupRecoveryDispatcher(executor);
        dispatcher.submit(new StartupRecoveryDispatcher.StartupRecoveryTask(
                "test-recovery", () -> executed.set(true), () -> rejected.set(true)));

        assertTrue(rejected.get());
        assertFalse(executed.get());
        verify(executor).execute(any(Runnable.class));
    }
}
