package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 活跃度热力图服务测试
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.10
 */
@DisplayName("活跃度热力图服务测试")
class ActivityHeatmapServiceTest {

    @Test
    @DisplayName("Pipeline 缺失 key 应保留 null 占位和结果顺序")
    void shouldPreserveMissingBitmapPosition() {
        byte[] first = {(byte) 0x80};
        byte[] third = {(byte) 0x01};

        List<byte[]> results = ActivityHeatmapService.mapPipelineResults(
                Arrays.asList(first, null, third));

        assertEquals(3, results.size());
        assertEquals(first, results.get(0));
        assertNull(results.get(1));
        assertEquals(third, results.get(2));
    }

    @Test
    @DisplayName("Pipeline 返回 null 列表时应快速失败")
    void shouldFailFastForNullPipelineResults() {
        assertThrows(IllegalStateException.class,
                () -> ActivityHeatmapService.mapPipelineResults(null));
    }

    @Test
    @DisplayName("帮派快照应使用新命名空间且名称缓存保持旧Key兼容")
    void shouldIsolateFactionSnapshotsAndKeepNameCacheCompatibility() {
        LocalDate date = LocalDate.of(2026, 7, 21);

        assertEquals("activity:v2:faction-snapshot-v2:online-count:20465:2026-07-21",
                ActivityRedisKeys.factionOnlineCount(20465, date));
        assertEquals("activity:v2:user:names", ActivityRedisKeys.USER_NAME_CACHE_KEY);
        assertEquals("activity:v2:faction:names", ActivityRedisKeys.FACTION_NAME_CACHE_KEY);
    }

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

        assertEquals(2, ActivityHeatmapService.countSamples(bitmap, 0));
        assertEquals(1, ActivityHeatmapService.countSamples(bitmap, 1));
        assertEquals(1, ActivityHeatmapService.countSamples(bitmap, 7));
        assertEquals(1, ActivityHeatmapService.countSamples(bitmap, 8));
        assertEquals(1, ActivityHeatmapService.countSamples(bitmap, 23));
    }

    @Test
    @DisplayName("缺失或截断的 Bitmap 按未活跃处理")
    void shouldTreatMissingBitmapBitsAsInactive() {
        assertEquals(0, ActivityHeatmapService.countSamples(null, 0));
        assertEquals(0, ActivityHeatmapService.countSamples(new byte[0], 0));
        assertEquals(1, ActivityHeatmapService.countSamples(new byte[]{(byte) 0x80}, 0));
        assertEquals(0, ActivityHeatmapService.countSamples(new byte[]{(byte) 0x80}, 2));
    }

    @Test
    @DisplayName("帮派槽数据按无符号字节累加指定小时的计数值")
    void shouldSumSlotValuesAsUnsignedBytes() {
        byte[] slotData = new byte[96];
        slotData[0] = (byte) 200;
        slotData[1] = (byte) 100;
        slotData[2] = (byte) 50;
        slotData[3] = (byte) 5;

        assertEquals(355, ActivityHeatmapService.sumSlotValues(slotData, 0));
        assertEquals(0, ActivityHeatmapService.sumSlotValues(slotData, 1));
    }

    @Test
    @DisplayName("null 槽数据返回 0")
    void shouldReturnZeroForNullSlotData() {
        assertEquals(0, ActivityHeatmapService.sumSlotValues(null, 0));
    }

    @Test
    @DisplayName("双证据 Bitmap 应按槽位 OR 去重而不是按数量截断")
    void shouldCountUnionSamplesByBitPosition() {
        byte[] statusActive = new byte[12];
        byte[] recentAction = new byte[12];
        setBit(statusActive, 0);
        setBit(statusActive, 1);
        setBit(recentAction, 1);
        setBit(recentAction, 2);

        byte[] observed = new byte[12];
        setBit(observed, 0);
        setBit(observed, 1);
        setBit(observed, 2);
        setBit(observed, 3);

        assertEquals(3, ActivityHeatmapService.countObservedUnionSamples(
                observed, statusActive, recentAction, 0));
    }

    @Test
    @DisplayName("双证据活跃位不在 observed 中时不应进入分子")
    void shouldExcludeActiveSlotsWithoutObservation() {
        byte[] observed = new byte[12];
        byte[] statusActive = new byte[12];
        setBit(observed, 0);
        setBit(statusActive, 0);
        setBit(statusActive, 1);

        assertEquals(1, ActivityHeatmapService.countObservedUnionSamples(
                observed, statusActive, null, 0));
    }

    @Test
    @DisplayName("双方共同采样应只统计同一槽位的交集")
    void shouldCountOnlyCommonObservedSlots() {
        byte[] faction1Observed = new byte[12];
        byte[] faction2Observed = new byte[12];
        setBit(faction1Observed, 0);
        setBit(faction1Observed, 1);
        setBit(faction2Observed, 1);
        setBit(faction2Observed, 2);

        assertEquals(1, ActivityHeatmapService.countCommonSamples(
                faction1Observed, faction2Observed, 0));
    }

    @Test
    @DisplayName("共同采样槽人数求和应忽略双方未同时观测的槽")
    void shouldSumValuesOnlyAtCommonObservedSlots() {
        byte[] faction1Observed = new byte[12];
        byte[] faction2Observed = new byte[12];
        byte[] faction1Counts = new byte[96];
        byte[] faction2Counts = new byte[96];
        setBit(faction1Observed, 0);
        setBit(faction1Observed, 1);
        setBit(faction2Observed, 1);
        setBit(faction2Observed, 2);
        faction1Counts[0] = 10;
        faction1Counts[1] = 20;
        faction2Counts[1] = 15;
        faction2Counts[2] = 30;

        assertEquals(20, ActivityHeatmapService.sumCommonSlotValues(
                faction1Counts, faction1Observed, faction2Observed, 0));
        assertEquals(15, ActivityHeatmapService.sumCommonSlotValues(
                faction2Counts, faction1Observed, faction2Observed, 0));
    }

    @Test
    @DisplayName("帮派人数聚合应只累计 observed 置位的槽")
    void shouldSumFactionValuesOnlyAtObservedSlots() {
        byte[] observed = new byte[12];
        byte[] counts = new byte[96];
        setBit(observed, 1);
        counts[0] = 40;
        counts[1] = 20;

        assertEquals(20, ActivityHeatmapService.sumObservedSlotValues(counts, observed, 0));
    }

    @Test
    @DisplayName("公共查询入口应拒绝非正数ID和越界天数")
    void shouldRejectInvalidQueryParameters() {
        ActivityHeatmapService service = new ActivityHeatmapService(null);

        assertThrows(IllegalArgumentException.class, () -> service.queryPersonalHeatmap(0, 28));
        assertThrows(IllegalArgumentException.class, () -> service.queryFactionHeatmap(1, 31));
        assertThrows(IllegalArgumentException.class, () -> service.compareFactions(1, -2, 28));
    }

    private static void setBit(byte[] bitmap, int offset) {
        int byteIndex = offset / Byte.SIZE;
        int bitIndex = offset % Byte.SIZE;
        bitmap[byteIndex] |= (byte) (1 << (Byte.SIZE - 1 - bitIndex));
    }
}
