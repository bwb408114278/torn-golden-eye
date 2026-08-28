package pn.torn.goldeneye.torn.service.activity.archive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.repository.dao.activity.TornActivityArchiveDayDAO;
import pn.torn.goldeneye.repository.dao.activity.TornActivityFactionDailyDAO;
import pn.torn.goldeneye.repository.dao.activity.TornActivityUserDailyDAO;
import pn.torn.goldeneye.repository.model.activity.TornActivityFactionDailyDO;
import pn.torn.goldeneye.repository.model.activity.TornActivityUserDailyDO;
import pn.torn.goldeneye.torn.service.activity.ActivityRedisKeys;

import java.time.LocalDate;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 活跃度V3日终归档服务测试
 * <p>
 * 覆盖"所有日包写成功才写 marker；任一写入异常不写 marker"与启动/定时共享防重入。
 * 不用 mock 模拟 PostgreSQL UPSERT 细节（由真实 Mapper 测试覆盖）。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.08.28
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("活跃度V3日终归档服务测试")
class ActivityDailyArchiveServiceTest {

    private static final LocalDate ARCHIVE_DATE = LocalDate.of(2026, 8, 27);
    private static final long USER_ID = 910001L;
    private static final long FACTION_ID = 920002L;

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private TornActivityUserDailyDAO userDailyDao;
    @Mock
    private TornActivityFactionDailyDAO factionDailyDao;
    @Mock
    private TornActivityArchiveDayDAO archiveDayDao;

    private ActivityDailyArchiveService service;

    @BeforeEach
    void setUp() {
        service = new ActivityDailyArchiveService(
                redisTemplate, userDailyDao, factionDailyDao, archiveDayDao);
    }

    @Test
    @DisplayName("用户与帮派日包全部写成功后才写 marker")
    void archiveDay_allPacksSuccess_writesMarkerLast() {
        stubIndexSets(Set.of(String.valueOf(USER_ID)), Set.of(String.valueOf(FACTION_ID)));
        stubMarkerMissing();
        byte[][] packData = {bitmap((byte) 0x80), bitmap((byte) 0x40), bitmap((byte) 0x20)};
        byte[][] factionData = {bitmap((byte) 0x80), slotValue((byte) 10), slotValue((byte) 4), slotValue((byte) 90)};
        stubPipeline(packData[0], packData[1], packData[2],
                factionData[0], factionData[1], factionData[2], factionData[3]);

        service.archiveDay(ARCHIVE_DATE);

        ArgumentCaptor<List<TornActivityUserDailyDO>> userCaptor = userPackCaptor();
        verify(userDailyDao).upsertBatch(userCaptor.capture());
        List<TornActivityUserDailyDO> userPacks = userCaptor.getValue();
        assertEquals(1, userPacks.size());
        assertEquals(USER_ID, userPacks.getFirst().getUserId());
        assertEquals(ARCHIVE_DATE, userPacks.getFirst().getActivityDate());
        assertEquals("V3", userPacks.getFirst().getDataVersion());
        assertArrayEquals(packData[0], userPacks.getFirst().getObservedBitmap());

        ArgumentCaptor<List<TornActivityFactionDailyDO>> factionCaptor = factionPackCaptor();
        verify(factionDailyDao).upsertBatch(factionCaptor.capture());
        List<TornActivityFactionDailyDO> factionPacks = factionCaptor.getValue();
        assertEquals(1, factionPacks.size());
        assertEquals(FACTION_ID, factionPacks.getFirst().getFactionId());
        assertArrayEquals(slotValue((byte) 10), factionPacks.getFirst().getActiveCounts());

        InOrder inOrder = inOrder(userDailyDao, factionDailyDao, archiveDayDao);
        inOrder.verify(userDailyDao).upsertBatch(any());
        inOrder.verify(factionDailyDao).upsertBatch(any());
        inOrder.verify(archiveDayDao).insertMarker(ARCHIVE_DATE);
    }

    @Test
    @DisplayName("帮派日包写入异常时不写 marker")
    void archiveDay_factionUpsertFails_neverWritesMarker() {
        stubIndexSets(Set.of(String.valueOf(USER_ID)), Set.of(String.valueOf(FACTION_ID)));
        stubMarkerMissing();
        stubPipeline(bitmap((byte) 0x80), bitmap((byte) 0x40), bitmap((byte) 0x20),
                bitmap((byte) 0x80), slotValue((byte) 10), slotValue((byte) 4), slotValue((byte) 90));
        when(factionDailyDao.upsertBatch(any())).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> service.archiveDay(ARCHIVE_DATE));

        verify(archiveDayDao, never()).insertMarker(any());
    }

    @Test
    @DisplayName("用户日包写入异常时不写 marker 且不继续帮派日包")
    void archiveDay_userUpsertFails_neverWritesMarker() {
        stubIndexSets(Set.of(String.valueOf(USER_ID)), Set.of(String.valueOf(FACTION_ID)));
        stubMarkerMissing();
        stubPipeline(bitmap((byte) 0x80), bitmap((byte) 0x40), bitmap((byte) 0x20),
                bitmap((byte) 0x80), slotValue((byte) 10), slotValue((byte) 4), slotValue((byte) 90));
        when(userDailyDao.upsertBatch(any())).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> service.archiveDay(ARCHIVE_DATE));

        verify(factionDailyDao, never()).upsertBatch(any());
        verify(archiveDayDao, never()).insertMarker(any());
    }

    @Test
    @DisplayName("全部对象缺少必需 Key 时跳过且不写 marker")
    void archiveDay_allIncomplete_skippedWithoutMarker() {
        stubIndexSets(Set.of(String.valueOf(USER_ID)), Set.of());
        stubMarkerMissing();
        stubPipeline(bitmap((byte) 0x80), null, bitmap((byte) 0x20));

        service.archiveDay(ARCHIVE_DATE);

        verify(userDailyDao, never()).upsertBatch(any());
        verify(archiveDayDao, never()).insertMarker(any());
    }

    @Test
    @DisplayName("marker 已存在的日期直接跳过")
    void archiveDay_markerExists_skips() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(ActivityRedisKeys.v3ArchiveUsers(ARCHIVE_DATE)))
                .thenReturn(Set.of(String.valueOf(USER_ID)));
        when(setOperations.members(ActivityRedisKeys.v3ArchiveFactions(ARCHIVE_DATE))).thenReturn(Set.of());
        when(archiveDayDao.selectArchivedDates(ARCHIVE_DATE, ARCHIVE_DATE))
                .thenReturn(java.util.Set.of(ARCHIVE_DATE));

        service.archiveDay(ARCHIVE_DATE);

        verify(userDailyDao, never()).upsertBatch(any());
        verify(archiveDayDao, never()).insertMarker(any());
    }

    @Test
    @DisplayName("归档索引为空时不产生任何数据库写入")
    void archiveDay_emptyIndexes_noWrites() {
        stubIndexSets(Set.of(), Set.of());

        service.archiveDay(ARCHIVE_DATE);

        verifyNoInteractions(archiveDayDao, userDailyDao, factionDailyDao);
    }

    @Test
    @DisplayName("防重入标记被持有时归档入口直接跳过")
    void archiveRecentUnarchivedDays_guardHeld_skips() {
        AtomicBoolean archiving = (AtomicBoolean) ReflectionTestUtils.getField(service, "archiving");
        assertNotNull(archiving);
        archiving.set(true);

        service.archiveRecentUnarchivedDays();
        service.scheduledArchive();

        verifyNoInteractions(archiveDayDao, userDailyDao, factionDailyDao, redisTemplate);
    }

    @Test
    @DisplayName("单日异常被捕获且 finally 释放防重入，入口可再次执行")
    void archiveRecentUnarchivedDays_dayFailureReleasesGuard() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(service::archiveRecentUnarchivedDays);

        AtomicBoolean archiving = (AtomicBoolean) ReflectionTestUtils.getField(service, "archiving");
        assertNotNull(archiving);
        assertFalse(archiving.get(), "异常后防重入标记应在 finally 中释放");

        assertDoesNotThrow(service::archiveRecentUnarchivedDays, "标记释放后入口应可再次执行");
        verify(setOperations, times(ActivityDailyArchiveService.COMPENSATION_DAYS * 2)).members(anyString());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<TornActivityUserDailyDO>> userPackCaptor() {
        return ArgumentCaptor.forClass((Class<List<TornActivityUserDailyDO>>) (Class<?>) List.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<TornActivityFactionDailyDO>> factionPackCaptor() {
        return ArgumentCaptor.forClass((Class<List<TornActivityFactionDailyDO>>) (Class<?>) List.class);
    }

    /**
     * 桩两个归档索引 Set
     */
    private void stubIndexSets(Set<String> userIds, Set<String> factionIds) {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(ActivityRedisKeys.v3ArchiveUsers(ARCHIVE_DATE))).thenReturn(userIds);
        when(setOperations.members(ActivityRedisKeys.v3ArchiveFactions(ARCHIVE_DATE))).thenReturn(factionIds);
    }

    /**
     * 桩 marker 查询为空（未归档）
     */
    private void stubMarkerMissing() {
        when(archiveDayDao.selectArchivedDates(eq(ARCHIVE_DATE), eq(ARCHIVE_DATE))).thenReturn(java.util.Set.of());
    }

    /**
     * 桩 Pipeline GET：按顺序逐个吐出预备响应，缺失 Key 用 null 表达
     */
    private void stubPipeline(byte[]... responses) {
        LinkedList<byte[]> queue = new LinkedList<>();
        for (byte[] response : responses) {
            queue.add(response);
        }
        when(redisTemplate.executePipelined(any(RedisCallback.class), any(RedisSerializer.class)))
                .thenAnswer(invocation -> {
                    RedisCallback<?> callback = invocation.getArgument(0);
                    RedisConnection connection = mock(RedisConnection.class);
                    RedisStringCommands stringCommands = mock(RedisStringCommands.class);
                    List<Object> served = new LinkedList<>();
                    when(connection.stringCommands()).thenReturn(stringCommands);
                    when(stringCommands.get(any(byte[].class))).thenAnswer(getInvocation -> {
                        byte[] value = queue.poll();
                        served.add(value);
                        return value;
                    });
                    callback.doInRedis(connection);
                    return served;
                });
    }

    /**
     * 构造 12 字节 Bitmap（96 槽），首字节为给定值
     */
    private static byte[] bitmap(byte firstByte) {
        byte[] data = new byte[12];
        data[0] = firstByte;
        return data;
    }

    /**
     * 构造 96 字节槽值，首槽为给定值
     */
    private static byte[] slotValue(byte firstSlot) {
        byte[] data = new byte[96];
        data[0] = firstSlot;
        return data;
    }
}
