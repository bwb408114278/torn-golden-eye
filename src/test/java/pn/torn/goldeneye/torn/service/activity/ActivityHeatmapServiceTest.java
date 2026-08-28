package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import pn.torn.goldeneye.repository.dao.activity.TornActivityFactionDailyDAO;
import pn.torn.goldeneye.repository.dao.activity.TornActivityUserDailyDAO;
import pn.torn.goldeneye.repository.model.activity.TornActivityFactionDailyDO;
import pn.torn.goldeneye.repository.model.activity.TornActivityUserDailyDO;
import pn.torn.goldeneye.torn.model.activity.*;
import pn.torn.goldeneye.torn.service.activity.query.ActivityHeatmapAggregator;
import pn.torn.goldeneye.torn.service.activity.query.ActivityHeatmapDataLoader;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 活跃度热力图服务测试
 * <p>
 * 覆盖 observed 分母、归档/V3 Redis/V2 Redis 加载优先级、V2 legacy 的 idleRatio=0、
 * 无数据仅标记 hasData=false 与部分范围仍可出图；不重复测试 renderer 像素。
 * Redis Pipeline 通过顺序队列桩表达"缺失 Key 保留 null 占位"。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.07.10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("活跃度热力图服务测试")
class ActivityHeatmapServiceTest {

    private static final LocalDate RANGE_START = LocalDate.of(2026, 8, 20);
    private static final LocalDate RANGE_END = LocalDate.of(2026, 8, 28);
    private static final LocalDate MIDDLE_DATE = LocalDate.of(2026, 8, 24);
    private static final long USER_ID = 54321L;
    private static final long FACTION_ID = 20465L;
    private static final long FACTION2_ID = 30465L;

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private TornActivityUserDailyDAO userDailyDao;
    @Mock
    private TornActivityFactionDailyDAO factionDailyDao;

    private ActivityHeatmapService service;
    private final List<Integer> pipelineCommandCounts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(hashOperations.get(any(), any())).thenReturn(null);
        ActivityHeatmapDataLoader dataLoader =
                new ActivityHeatmapDataLoader(redisTemplate, userDailyDao, factionDailyDao);
        service = new ActivityHeatmapService(redisTemplate, dataLoader);
        pipelineCommandCounts.clear();
    }

    @Test
    @DisplayName("V3 归档日包优先于 Redis：activeRate 以 observed 为分母，idleRatio 按 I/(A+I) 聚合")
    void queryPersonalHeatmap_archivedV3Day_aggregatesRateAndIdleRatio() {
        when(userDailyDao.selectByUserAndDateRange(USER_ID, RANGE_START, RANGE_END))
                .thenReturn(List.of(buildUserDaily(MIDDLE_DATE, (byte) 0xF0, new int[]{0, 1}, new int[]{2})));
        stubPipelineGet(nulls(8 * 3), nulls(8 * 3));

        PersonalActivityHeatmapVO vo = service.queryPersonalHeatmap(USER_ID, range());

        int dow = dowOf(MIDDLE_DATE);
        assertTrue(vo.isHasData());
        assertEquals(0.5, vo.getActiveRate()[dow][0], 1e-9, "4 个 observed 槽中 2 个 active");
        assertEquals(1.0 / 3, vo.getIdleRatio()[dow][0], 1e-9, "idle 采样 1 / (active 2 + idle 1)");
        assertEquals(4, vo.getObservedSamples()[dow][0]);
        assertFalse(vo.isLegacyDataIncluded());
        assertTrue(vo.getSubtitle().contains("有效采样覆盖率"), "副标题第一行应为覆盖率说明");
        assertTrue(vo.getNoticeMessage().contains("仅覆盖 1 个采样日"), "部分覆盖应提示采样日不足");
    }

    @Test
    @DisplayName("V2 legacy 回退日：active 为双证据 OR，idleRatio 固定 0 并输出 legacy 提示")
    void queryPersonalHeatmap_v2LegacyDay_idleRatioZeroWithLegacyNotice() {
        when(userDailyDao.selectByUserAndDateRange(USER_ID, RANGE_START, RANGE_END)).thenReturn(List.of());
        List<byte[]> v3Stage = nulls(9 * 3);
        List<byte[]> v2Stage = nulls(9 * 3);
        int legacyIndex = dayIndex(MIDDLE_DATE) * 3;
        v2Stage.set(legacyIndex, bitmap(0xF0));
        v2Stage.set(legacyIndex + 1, bitmap(0xC0));
        v2Stage.set(legacyIndex + 2, bitmap(0x40));
        stubPipelineGet(v3Stage, v2Stage);

        PersonalActivityHeatmapVO vo = service.queryPersonalHeatmap(USER_ID, range());

        int dow = dowOf(MIDDLE_DATE);
        assertTrue(vo.isHasData());
        assertEquals(0.5, vo.getActiveRate()[dow][0], 1e-9,
                "status-active(2槽) OR recent-action(1槽重叠) 去重后为 2/4");
        assertEquals(0.0, vo.getIdleRatio()[dow][0], "V2 legacy 无法区分 Idle，idleRatio 固定为 0");
        assertTrue(vo.isLegacyDataIncluded());
        assertTrue(vo.getNoticeMessage().contains("部分历史采样未区分 Idle"));
    }

    @Test
    @DisplayName("V3 companion key 缺失时应回退同日 V2，而不是展示 V3 零值")
    void queryPersonalHeatmap_incompleteV3_fallsBackToV2() {
        when(userDailyDao.selectByUserAndDateRange(USER_ID, RANGE_START, RANGE_END)).thenReturn(List.of());
        List<byte[]> v3Stage = nulls(9 * 3);
        int incompleteIndex = dayIndex(MIDDLE_DATE) * 3;
        v3Stage.set(incompleteIndex, bitmap(0x80));
        List<byte[]> v2Stage = nulls(9 * 3);
        v2Stage.set(incompleteIndex, bitmap(0x80));
        v2Stage.set(incompleteIndex + 1, bitmap(0x80));
        v2Stage.set(incompleteIndex + 2, bitmap(0));
        stubPipelineGet(v3Stage, v2Stage);

        PersonalActivityHeatmapVO vo = service.queryPersonalHeatmap(USER_ID, range());

        int dow = dowOf(MIDDLE_DATE);
        assertEquals(1, vo.getObservedSamples()[dow][0]);
        assertEquals(1.0, vo.getActiveRate()[dow][0], 1e-9);
        assertTrue(vo.isLegacyDataIncluded());
    }

    @Test
    @DisplayName("超长 FROM 范围的 Redis 请求仅覆盖最近 30 天")
    void queryPersonalHeatmap_longRange_limitsRedisWindow() {
        LocalDate oldDate = LocalDate.of(1970, 1, 1);
        ActivityQueryRange longRange = new ActivityQueryRange(oldDate, RANGE_END, ActivityQueryRangeModeEnum.FROM);
        when(userDailyDao.selectByUserAndDateRange(USER_ID, oldDate, RANGE_END)).thenReturn(List.of());
        stubPipelineGet(nulls(30 * 3), nulls(30 * 3));

        PersonalActivityHeatmapVO vo = service.queryPersonalHeatmap(USER_ID, longRange);

        assertFalse(vo.isHasData());
        verify(userDailyDao).selectByUserAndDateRange(USER_ID, oldDate, RANGE_END);
        assertEquals(List.of(90, 90), pipelineCommandCounts,
                "Redis V3/V2 各应只覆盖最近 30 天");
    }

    @Test
    @DisplayName("范围内无任何有效 observed 槽时 hasData=false 且不生成部分覆盖提示")
    void queryPersonalHeatmap_noData_marksHasDataFalse() {
        when(userDailyDao.selectByUserAndDateRange(USER_ID, RANGE_START, RANGE_END)).thenReturn(List.of());
        stubPipelineGet(nulls(9 * 3), nulls(9 * 3));

        PersonalActivityHeatmapVO vo = service.queryPersonalHeatmap(USER_ID, range());

        assertFalse(vo.isHasData());
        assertEquals(0.0, vo.getCoverage());
        assertNull(vo.getNoticeMessage());
        assertEquals(9, vo.getTotalDays());
    }

    @Test
    @DisplayName("帮派 V3 日包：格内平均有效活跃人数，idleRatio 按 I/(A+I)，副标题注明口径")
    void queryFactionHeatmap_v3Day_averageActiveAndIdleRatio() {
        when(factionDailyDao.selectByFactionAndDateRange(FACTION_ID, RANGE_START, RANGE_END))
                .thenReturn(List.of(buildFactionDaily(MIDDLE_DATE, (byte) 0xF0,
                        new int[]{10, 20, 30, 40}, new int[]{0, 0, 10, 10}, new int[]{90, 90, 90, 90})));
        stubPipelineGet(nulls(8 * 4), nulls(8 * 3));

        FactionActivityHeatmapVO vo = service.queryFactionHeatmap(FACTION_ID, range());

        int dow = dowOf(MIDDLE_DATE);
        assertTrue(vo.isHasData());
        assertEquals(25.0, vo.getAverageOnlineCount()[dow][0], 1e-9, "(10+20+30+40)/4 个 observed 槽");
        assertEquals(20.0 / 120, vo.getIdleRatio()[dow][0], 1e-9, "20/(100+20)");
        assertEquals(4, vo.getObservedSamples()[dow][0]);
        assertTrue(vo.getSubtitle().contains("平均有效活跃人数"));
    }

    @Test
    @DisplayName("V2 帮派日包按 online/member/observed 顺序解包，并保持 legacy 语义")
    void queryFactionHeatmap_v2Legacy_unpacksOnlineMemberObservedOrder() {
        when(factionDailyDao.selectByFactionAndDateRange(FACTION_ID, RANGE_START, RANGE_END))
                .thenReturn(List.of());
        List<byte[]> v3Stage = nulls(9 * 4);
        List<byte[]> v2Stage = nulls(9 * 3);
        int legacyIndex = dayIndex(MIDDLE_DATE) * 3;
        v2Stage.set(legacyIndex, slots(new int[]{10}));
        v2Stage.set(legacyIndex + 1, slots(new int[]{50}));
        v2Stage.set(legacyIndex + 2, bitmap(0x80));
        stubPipelineGet(v3Stage, v2Stage);

        FactionActivityHeatmapVO vo = service.queryFactionHeatmap(FACTION_ID, range());

        int dow = dowOf(MIDDLE_DATE);
        assertEquals(1, vo.getObservedSamples()[dow][0]);
        assertEquals(10.0, vo.getAverageOnlineCount()[dow][0], 1e-9);
        assertEquals(0.0, vo.getIdleRatio()[dow][0], 1e-9);
        assertTrue(vo.isLegacyDataIncluded());
    }

    @Test
    @DisplayName("帮派 V3 日包不完整时应回退同日 V2")
    void queryFactionHeatmap_incompleteV3_fallsBackToV2() {
        when(factionDailyDao.selectByFactionAndDateRange(FACTION_ID, RANGE_START, RANGE_END))
                .thenReturn(List.of());
        List<byte[]> v3Stage = nulls(9 * 4);
        int incompleteIndex = dayIndex(MIDDLE_DATE) * 4;
        v3Stage.set(incompleteIndex, bitmap(0x80));
        List<byte[]> v2Stage = nulls(9 * 3);
        int legacyIndex = dayIndex(MIDDLE_DATE) * 3;
        v2Stage.set(legacyIndex, slots(new int[]{12}));
        v2Stage.set(legacyIndex + 1, slots(new int[]{60}));
        v2Stage.set(legacyIndex + 2, bitmap(0x80));
        stubPipelineGet(v3Stage, v2Stage);

        FactionActivityHeatmapVO vo = service.queryFactionHeatmap(FACTION_ID, range());

        int dow = dowOf(MIDDLE_DATE);
        assertEquals(1, vo.getObservedSamples()[dow][0]);
        assertEquals(12.0, vo.getAverageOnlineCount()[dow][0], 1e-9);
        assertEquals(0.0, vo.getIdleRatio()[dow][0], 1e-9);
        assertTrue(vo.isLegacyDataIncluded());
    }

    @Test
    @DisplayName("对比图仅累计双方共同 observed 槽，副标题注明 Idle 不计入对比")
    void compareFactions_commonSlotsOnlyWithComparisonSubtitle() {
        when(factionDailyDao.selectByFactionAndDateRange(FACTION_ID, RANGE_START, RANGE_END))
                .thenReturn(List.of(buildFactionDaily(MIDDLE_DATE, (byte) 0x80,
                        new int[]{10, 0, 0, 0}, new int[]{0, 0, 0, 0}, new int[]{50, 0, 0, 0})));
        when(factionDailyDao.selectByFactionAndDateRange(FACTION2_ID, RANGE_START, RANGE_END))
                .thenReturn(List.of());

        List<byte[]> faction1V3Stage = nulls(8 * 4);
        List<byte[]> faction1V2Stage = nulls(8 * 3);
        List<byte[]> faction2V3Stage = nulls(9 * 4);
        int faction2Index = dayIndex(MIDDLE_DATE) * 4;
        faction2V3Stage.set(faction2Index, bitmap(0x80));
        faction2V3Stage.set(faction2Index + 1, slots(new int[]{20, 0, 0, 0}));
        faction2V3Stage.set(faction2Index + 2, slots(new int[]{5, 0, 0, 0}));
        faction2V3Stage.set(faction2Index + 3, slots(new int[]{60, 0, 0, 0}));
        List<byte[]> faction2V2Stage = nulls(8 * 3);
        stubPipelineGet(faction1V3Stage, faction1V2Stage, faction2V3Stage, faction2V2Stage);

        ActivityComparisonHeatmapVO vo = service.compareFactions(FACTION_ID, FACTION2_ID, range());

        int dow = dowOf(MIDDLE_DATE);
        assertTrue(vo.isHasData());
        assertTrue(vo.getBothObserved()[dow][0], "双方 slot0 均观测时才参与对比");
        assertEquals(10.0, vo.getFaction1AverageOnline()[dow][0], 1e-9);
        assertEquals(20.0, vo.getFaction2AverageOnline()[dow][0], 1e-9);
        assertTrue(vo.getSubtitle().contains("仅对比有效活跃人数；Idle 不计入对比"));
    }

    @Test
    @DisplayName("同一日期优先取 V3 归档值，不与 Redis 重复累计")
    void loadUserDays_archiveDayWinsOverRedis() {
        when(userDailyDao.selectByUserAndDateRange(USER_ID, RANGE_START, RANGE_END))
                .thenReturn(List.of(buildUserDaily(MIDDLE_DATE, (byte) 0x80, new int[]{0}, new int[]{})));
        List<byte[]> v3Stage = nulls(8 * 3);
        List<byte[]> v2Stage = nulls(8 * 3);
        stubPipelineGet(v3Stage, v2Stage);

        PersonalActivityHeatmapVO vo = service.queryPersonalHeatmap(USER_ID, range());

        int dow = dowOf(MIDDLE_DATE);
        assertEquals(1, vo.getObservedSamples()[dow][0], "归档行仅 slot0 observed，Redis 值不得重复累计");
        assertEquals(1.0, vo.getActiveRate()[dow][0], 1e-9);
    }

    @Test
    @DisplayName("公共查询入口应拒绝非正数目标 ID")
    void shouldRejectInvalidQueryTargets() {
        ActivityQueryRange range = range();

        assertThrows(IllegalArgumentException.class, () -> service.queryPersonalHeatmap(0, range));
        assertThrows(IllegalArgumentException.class, () -> service.queryFactionHeatmap(-1, range));
        assertThrows(IllegalArgumentException.class, () -> service.compareFactions(0, FACTION2_ID, range));
    }

    // ==================== Bitmap 位序工具（MSB-first） ====================

    @Test
    @DisplayName("按 Redis Bitmap 的 MSB-first 位序统计每小时活跃采样数")
    void shouldCountHourlySamplesWithRedisBitOrder() {
        byte[] bitmap = new byte[12];
        setBit(bitmap, 0);
        setBit(bitmap, 3);
        setBit(bitmap, 4);
        setBit(bitmap, 31);
        setBit(bitmap, 32);
        setBit(bitmap, 95);

        assertEquals(2, ActivityHeatmapAggregator.countSamples(bitmap, 0));
        assertEquals(1, ActivityHeatmapAggregator.countSamples(bitmap, 1));
        assertEquals(1, ActivityHeatmapAggregator.countSamples(bitmap, 7));
        assertEquals(1, ActivityHeatmapAggregator.countSamples(bitmap, 8));
        assertEquals(1, ActivityHeatmapAggregator.countSamples(bitmap, 23));
    }

    @Test
    @DisplayName("缺失或截断的 Bitmap 按未活跃处理")
    void shouldTreatMissingBitmapBitsAsInactive() {
        assertEquals(0, ActivityHeatmapAggregator.countSamples(null, 0));
        assertEquals(0, ActivityHeatmapAggregator.countSamples(new byte[0], 0));
        assertEquals(1, ActivityHeatmapAggregator.countSamples(new byte[]{(byte) 0x80}, 0));
        assertEquals(0, ActivityHeatmapAggregator.countSamples(new byte[]{(byte) 0x80}, 2));
        assertFalse(ActivityHeatmapAggregator.isBitSet(null, 0));
    }

    @Test
    @DisplayName("证据 Bitmap 仅在 observed 置位槽内计入 active/idle 分子")
    void shouldCountEvidenceSamplesOnlyWithinObservedSlots() {
        byte[] observed = new byte[12];
        byte[] active = new byte[12];
        setBit(observed, 0);
        setBit(active, 0);
        setBit(active, 1);

        assertEquals(1, ActivityHeatmapAggregator.countActiveSamples(observed, active, 0));
    }

    @Test
    @DisplayName("双方共同采样应只统计同一槽位的交集及其槽值")
    void shouldSumValuesOnlyAtCommonObservedSlots() {
        byte[] faction1Observed = new byte[12];
        byte[] faction2Observed = new byte[12];
        byte[] faction1Counts = new byte[96];
        setBit(faction1Observed, 0);
        setBit(faction1Observed, 1);
        setBit(faction2Observed, 1);
        setBit(faction2Observed, 2);
        faction1Counts[0] = 10;
        faction1Counts[1] = 20;

        assertEquals(1, ActivityHeatmapAggregator.countCommonSamples(faction1Observed, faction2Observed, 0));
        assertEquals(20, ActivityHeatmapAggregator.sumCommonSlotValues(
                faction1Counts, faction1Observed, faction2Observed, 0));
    }

    @Test
    @DisplayName("帮派人数聚合应只累计 observed 置位的槽")
    void shouldSumFactionValuesOnlyAtObservedSlots() {
        byte[] observed = new byte[12];
        byte[] counts = new byte[96];
        setBit(observed, 1);
        counts[0] = 40;
        counts[1] = 20;

        assertEquals(20, ActivityHeatmapAggregator.sumObservedSlotValues(counts, observed, 0));
    }

    // ==================== 测试工具 ====================

    private static ActivityQueryRange range() {
        return new ActivityQueryRange(RANGE_START, RANGE_END, ActivityQueryRangeModeEnum.FROM);
    }

    /**
     * 目标日期在 9 天范围中的下标（升序）
     */
    private static int dayIndex(LocalDate date) {
        return (int) (date.toEpochDay() - RANGE_START.toEpochDay());
    }

    /**
     * 与聚合器一致的星期行索引（周一=0，周日=6）
     */
    private static int dowOf(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SUNDAY ? 6 : dayOfWeek.getValue() - 1;
    }

    private static TornActivityUserDailyDO buildUserDaily(LocalDate date, byte observedByte,
                                                          int[] activeSlots, int[] idleSlots) {
        TornActivityUserDailyDO row = new TornActivityUserDailyDO();
        row.setUserId(USER_ID);
        row.setActivityDate(date);
        row.setObservedBitmap(bitmap(observedByte));
        byte[] active = new byte[12];
        for (int slot : activeSlots) {
            setBit(active, slot);
        }
        byte[] idle = new byte[12];
        for (int slot : idleSlots) {
            setBit(idle, slot);
        }
        row.setActiveBitmap(active);
        row.setIdleBitmap(idle);
        row.setDataVersion("V3");
        return row;
    }

    private static TornActivityFactionDailyDO buildFactionDaily(LocalDate date, byte observedByte,
                                                                int[] activeSlotValues, int[] idleSlotValues,
                                                                int[] memberSlotValues) {
        TornActivityFactionDailyDO row = new TornActivityFactionDailyDO();
        row.setFactionId(FACTION_ID);
        row.setActivityDate(date);
        row.setObservedBitmap(bitmap(observedByte));
        row.setActiveCounts(slots(activeSlotValues));
        row.setIdleCounts(slots(idleSlotValues));
        row.setMemberCounts(slots(memberSlotValues));
        row.setDataVersion("V3");
        return row;
    }

    /**
     * 构造 12 字节 Bitmap，首字节为给定值
     */
    private static byte[] bitmap(int firstByte) {
        byte[] data = new byte[12];
        data[0] = (byte) firstByte;
        return data;
    }

    /**
     * 构造 96 字节槽值数组，前 4 槽使用给定值
     */
    private static byte[] slots(int[] firstFourSlotValues) {
        byte[] data = new byte[96];
        for (int i = 0; i < firstFourSlotValues.length && i < 4; i++) {
            data[i] = (byte) firstFourSlotValues[i];
        }
        return data;
    }

    /**
     * 构造 n 个 null 的 Pipeline 响应（表达缺失 Key）
     */
    private static List<byte[]> nulls(int count) {
        List<byte[]> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(null);
        }
        return values;
    }

    /**
     * 顺序队列桩：多次 Pipeline GET 按调用顺序逐个吐出响应，缺失 Key 保留 null 占位
     */
    @SafeVarargs
    private final void stubPipelineGet(List<byte[]>... stages) {
        LinkedList<byte[]> queue = new LinkedList<>();
        for (List<byte[]> stage : stages) {
            queue.addAll(stage);
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
                    pipelineCommandCounts.add(served.size());
                    return served;
                });
    }

    /**
     * 按 Redis MSB-first 位序置位
     */
    private static void setBit(byte[] bitmap, int offset) {
        int byteIndex = offset / Byte.SIZE;
        int bitIndex = offset % Byte.SIZE;
        bitmap[byteIndex] |= (byte) (1 << (Byte.SIZE - 1 - bitIndex));
    }
}
