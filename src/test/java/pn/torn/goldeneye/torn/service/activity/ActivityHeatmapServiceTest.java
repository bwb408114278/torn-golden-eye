package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 活跃度热力图服务测试
 *
 * @author Bai
 * @version 1.2.9
 * @since 2026.07.10
 */
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
        assertEquals(null, results.get(1));
        assertEquals(third, results.get(2));
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

        assertEquals(2, ActivityHeatmapService.countActiveSamples(bitmap, 0));
        assertEquals(1, ActivityHeatmapService.countActiveSamples(bitmap, 1));
        assertEquals(1, ActivityHeatmapService.countActiveSamples(bitmap, 7));
        assertEquals(1, ActivityHeatmapService.countActiveSamples(bitmap, 8));
        assertEquals(1, ActivityHeatmapService.countActiveSamples(bitmap, 23));
    }

    @Test
    @DisplayName("缺失或截断的 Bitmap 按未活跃处理")
    void shouldTreatMissingBitmapBitsAsInactive() {
        assertEquals(0, ActivityHeatmapService.countActiveSamples(null, 0));
        assertEquals(0, ActivityHeatmapService.countActiveSamples(new byte[0], 0));
        assertEquals(1, ActivityHeatmapService.countActiveSamples(new byte[]{(byte) 0x80}, 0));
        assertEquals(0, ActivityHeatmapService.countActiveSamples(new byte[]{(byte) 0x80}, 2));
    }

    private static void setBit(byte[] bitmap, int offset) {
        int byteIndex = offset / Byte.SIZE;
        int bitIndex = offset % Byte.SIZE;
        bitmap[byteIndex] |= (byte) (1 << (Byte.SIZE - 1 - bitIndex));
    }
}
