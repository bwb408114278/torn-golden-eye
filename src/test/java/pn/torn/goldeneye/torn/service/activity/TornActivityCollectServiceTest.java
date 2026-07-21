package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 活跃度采集服务测试
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.10
 */
@DisplayName("活跃度采集服务测试")
class TornActivityCollectServiceTest {

    @Test
    @DisplayName("同一时间快照应正确计算午夜两侧槽位")
    void shouldCalculateSlotFromSameTimeSnapshot() {
        assertEquals(95, TornActivityCollectService.calculateSlotIndex(
                LocalDateTime.of(2026, 7, 10, 23, 59, 59)));
        assertEquals(0, TornActivityCollectService.calculateSlotIndex(
                LocalDateTime.of(2026, 7, 11, 0, 0, 0)));
    }

    @Test
    @DisplayName("帮派槽位值在 0-255 范围内应编码为单字节")
    void shouldEncodeSlotValueForValidRange() {
        byte[] bytes = TornActivityCollectService.encodeSlotValue(100);
        assertEquals(1, bytes.length);
        assertEquals(100, bytes[0] & 0xFF);
    }

    @Test
    @DisplayName("帮派槽位值超出 255 应抛出异常")
    void shouldThrowForSlotValueExceeding255() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> TornActivityCollectService.encodeSlotValue(256));

        assertEquals("帮派槽位值超出 1 字节范围: 256", exception.getMessage());
    }

    @Test
    @DisplayName("双证据重叠成员应按用户ID并集计数")
    void shouldCountEstimatedActiveUsersWithoutDuplicates() {
        int count = TornActivityCollectService.countEstimatedActiveUsers(
                List.of(1L, 2L), List.of(2L, 3L));

        assertEquals(3, count);
    }

    @Test
    @DisplayName("同一槽位重采时非活跃成员应生成 false 状态以清除旧位")
    void shouldBuildFalseStateForInactiveMember() {
        Map<Long, Boolean> states = TornActivityCollectService.buildEvidenceStates(
                List.of(1L, 2L, 3L), List.of(1L, 3L));

        assertEquals(Map.of(1L, true, 2L, false, 3L, true), states);
    }

    @Test
    @DisplayName("任务提交被拒绝时应等待已提交任务并将剩余任务计为失败")
    void shouldWaitSubmittedTasksWhenExecutorRejects() {
        TornApi tornApi = mock(TornApi.class);
        SimpleAsyncTaskExecutor executor = mock(SimpleAsyncTaskExecutor.class);
        AtomicInteger submitted = new AtomicInteger();
        doAnswer(invocation -> {
            if (submitted.getAndIncrement() == 0) {
                invocation.<Runnable>getArgument(0).run();
                return null;
            }
            throw new RejectedExecutionException("executor stopped");
        }).when(executor).execute(any(Runnable.class));
        when(tornApi.sendRequest(any(), any())).thenReturn(null);

        TornActivityCollectService service = new TornActivityCollectService(
                tornApi,
                mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                mock(DynamicTaskService.class),
                mock(SysSettingManager.class),
                mock(ProjectProperty.class),
                executor);

        TornActivityCollectService.BatchResult result = service.processBatch(List.of(1L, 2L, 3L));

        assertEquals(0, result.successCount());
        assertEquals(3, result.failureCount());
        assertEquals(2, submitted.get());
    }

    @Test
    @DisplayName("任务提交被拒绝后应释放单实例重入标记")
    void shouldReleaseReentryGuardAfterExecutorRejects() {
        SimpleAsyncTaskExecutor executor = mock(SimpleAsyncTaskExecutor.class);
        doAnswer(invocation -> {
            throw new RejectedExecutionException("executor stopped");
        }).when(executor).execute(any(Runnable.class));

        TornActivityCollectService service = new TornActivityCollectService(
                mock(TornApi.class),
                mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                mock(DynamicTaskService.class),
                mock(SysSettingManager.class),
                mock(ProjectProperty.class),
                executor);
        AtomicReference<List<Long>> trackedFactionIds = new AtomicReference<>(List.of(1L));
        ReflectionTestUtils.setField(service, "trackedFactionIds", trackedFactionIds);

        service.collectActivity();

        AtomicBoolean collecting = (AtomicBoolean) ReflectionTestUtils.getField(service, "collecting");
        assertNotNull(collecting, "collecting 字段不应为 null");
        assertFalse(collecting.get(), "采集重入标记应已释放");
    }
}
