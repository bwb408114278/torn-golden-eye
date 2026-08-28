package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.base.torn.TornReqParamV2;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.model.activity.TornFactionHofDTO;
import pn.torn.goldeneye.torn.model.activity.TornFactionHofVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 活跃度采集服务测试
 *
 * @author Bai
 * @version 1.5.0
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
    @DisplayName("Gold+ 与配置帮派应按 ID 去重并升序合并")
    void shouldMergeGoldPlusAndSettingFactionsById() {
        TornSettingFactionDO setting = new TornSettingFactionDO();
        setting.setId(3L);

        List<Long> merged = TornActivityCollectService.mergeTrackedFactionIds(
                List.of(2L, 1L, 2L), List.of(setting, buildSettingFaction(1L)));

        assertEquals(List.of(1L, 2L, 3L), merged);
    }

    @Test
    @DisplayName("HoF 来源为空时配置低段位帮派仍应纳入合并结果")
    void shouldKeepSettingFactionsWhenGoldPlusEmpty() {
        List<Long> merged = TornActivityCollectService.mergeTrackedFactionIds(
                List.of(), List.of(buildSettingFaction(88L), buildSettingFaction(null)));

        assertEquals(List.of(88L), merged);
    }

    @Test
    @DisplayName("配置来源为空时仅保留 Gold+ 来源")
    void shouldKeepGoldPlusOnlyWhenSettingEmpty() {
        assertEquals(List.of(4L, 9L),
                TornActivityCollectService.mergeTrackedFactionIds(List.of(9L, 4L), List.of()));
    }

    @Test
    @DisplayName("重启恢复 Gold+ 来源后 HoF 失败仍保留非配置帮派")
    void shouldKeepRestoredGoldPlusWhenHofRefreshFails() {
        TornApi tornApi = mock(TornApi.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        TornSettingFactionManager settingFactionManager = mock(TornSettingFactionManager.class);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(ActivityRedisKeys.v3TrackedGoldPlus())).thenReturn(Set.of("777"));
        when(setOperations.members("faction:tracked")).thenReturn(Set.of("777"));
        when(settingFactionManager.getList()).thenReturn(List.of(buildSettingFaction(888L)));
        doReturn(null).when(tornApi).sendRequest(any(TornFactionHofDTO.class), eq(TornFactionHofVO.class));

        TornActivityCollectService service = new TornActivityCollectService(
                tornApi, redisTemplate, mock(DynamicTaskService.class), mock(SysSettingManager.class),
                mock(ProjectProperty.class), settingFactionManager,
                mock(SimpleAsyncTaskExecutor.class));

        ReflectionTestUtils.invokeMethod(service, "loadFactionListFromRedis");
        service.refreshFactionList();

        @SuppressWarnings("unchecked")
        AtomicReference<List<Long>> tracked = (AtomicReference<List<Long>>) ReflectionTestUtils
                .getField(service, "trackedFactionIds");
        assertEquals(List.of(777L, 888L), tracked.get());
    }

    @Test
    @DisplayName("成功采集应在同一 Pipeline 写入当天 archive date ZSET")
    void collectFaction_writesArchiveDateIndexInPipeline() {
        TornApi tornApi = mock(TornApi.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        TornFactionMemberVO member = new TornFactionMemberVO();
        member.setId(1001L);
        member.setName("member");
        TornFactionMemberListVO response = new TornFactionMemberListVO();
        response.setMembers(List.of(member));
        when(tornApi.sendRequest(any(TornReqParamV2.class), eq(TornFactionMemberListVO.class)))
                .thenReturn(response);
        RedisZSetCommands zSetCommands = mock(RedisZSetCommands.class);
        RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            RedisConnection connection = mock(RedisConnection.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
            when(connection.zSetCommands()).thenReturn(zSetCommands);
            when(connection.keyCommands()).thenReturn(keyCommands);
            callback.doInRedis(connection);
            return List.of();
        });

        TornActivityCollectService service = new TornActivityCollectService(
                tornApi, redisTemplate, mock(DynamicTaskService.class), mock(SysSettingManager.class),
                mock(ProjectProperty.class), mock(TornSettingFactionManager.class),
                mock(SimpleAsyncTaskExecutor.class));

        Boolean result = ReflectionTestUtils.invokeMethod(service, "collectFaction", 2002L);

        assertTrue(result);
        verify(redisTemplate).executePipelined(any(RedisCallback.class));
        LocalDate today = LocalDate.now(TornActivityCollectService.HEATMAP_ZONE);
        verify(zSetCommands).zAdd(
                eq(ActivityRedisKeys.v3ArchiveDates().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                eq((double) today.toEpochDay()),
                eq(today.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        verify(keyCommands).expire(
                eq(ActivityRedisKeys.v3ArchiveDates().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                eq(30L * 24 * 60 * 60));
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
        when(tornApi.sendRequest(any(TornReqParamV2.class), any())).thenReturn(null);

        TornActivityCollectService service = new TornActivityCollectService(
                tornApi,
                mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                mock(DynamicTaskService.class),
                mock(SysSettingManager.class),
                mock(ProjectProperty.class),
                mock(TornSettingFactionManager.class),
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
                mock(TornSettingFactionManager.class),
                executor);
        AtomicReference<List<Long>> trackedFactionIds = new AtomicReference<>(List.of(1L));
        ReflectionTestUtils.setField(service, "trackedFactionIds", trackedFactionIds);

        service.collectActivity();

        AtomicBoolean collecting = (AtomicBoolean) ReflectionTestUtils.getField(service, "collecting");
        assertNotNull(collecting, "collecting 字段不应为 null");
        assertFalse(collecting.get(), "采集重入标记应已释放");
    }

    private static TornSettingFactionDO buildSettingFaction(Long factionId) {
        TornSettingFactionDO setting = new TornSettingFactionDO();
        setting.setId(factionId);
        return setting;
    }
}
