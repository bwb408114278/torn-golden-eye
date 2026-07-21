package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    private static void setBit(byte[] bitmap, int offset) {
        int byteIndex = offset / Byte.SIZE;
        int bitIndex = offset % Byte.SIZE;
        bitmap[byteIndex] |= (byte) (1 << (Byte.SIZE - 1 - bitIndex));
    }
}
