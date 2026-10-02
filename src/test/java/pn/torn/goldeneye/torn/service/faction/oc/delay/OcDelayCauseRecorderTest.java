package pn.torn.goldeneye.torn.service.faction.oc.delay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.oc.delay.OcDelayCauseEntry;
import pn.torn.goldeneye.torn.model.faction.oc.delay.OcDelayReasonEnum;
import pn.torn.goldeneye.torn.service.faction.oc.delay.OcDelayReasonResolver.OcDelayReason;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OC延误归因编解码与段结算测试。
 *
 * @author Bai
 * @version 1.6.7
 * @since 2026.10.02
 */
@DisplayName("OC延误归因编解码与段结算测试")
class OcDelayCauseRecorderTest {
    private static final LocalDateTime FIRST_SAMPLE = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final long FIRST_SAMPLE_MINUTE = FIRST_SAMPLE.toEpochSecond(ZoneOffset.UTC) / 60;

    private final OcDelayCauseRecorder recorder = new OcDelayCauseRecorder();

    @Test
    @DisplayName("编码：按 userId|原因|道具ID|段起始分钟|已累计分钟 落库并可无损解析")
    void merge_shouldEncodeEntryInDocumentedFormat() {
        String travelCause = recorder.merge(null, Map.of(2975823L, reason(OcDelayReasonEnum.TRAVEL)),
                FIRST_SAMPLE);
        String itemCause = recorder.merge(null, Map.of(2455214L, reason(OcDelayReasonEnum.ITEM, 1430)),
                FIRST_SAMPLE);

        assertEquals("2975823|TRAVEL|0|" + FIRST_SAMPLE_MINUTE + "|0", travelCause);
        assertEquals("2455214|ITEM|1430|" + FIRST_SAMPLE_MINUTE + "|0", itemCause);
        assertEquals("2975823|TRAVEL|0|0|115", recorder.settle(travelCause, FIRST_SAMPLE.plusMinutes(115)));
        assertEquals("2455214|ITEM|1430|0|60", recorder.settle(itemCause, FIRST_SAMPLE.plusMinutes(60)));
    }

    @Test
    @DisplayName("采样合并：未出现的成员闭合段并累计，原因保持首次观测值，新成员并集加入")
    void merge_shouldAccumulateSegmentsAndKeepFirstReason() {
        String first = recorder.merge(null, Map.of(
                1L, reason(OcDelayReasonEnum.ITEM, 1430),
                2L, reason(OcDelayReasonEnum.TRAVEL)), FIRST_SAMPLE);
        String second = recorder.merge(first, Map.of(
                2L, reason(OcDelayReasonEnum.TRAVEL),
                3L, reason(OcDelayReasonEnum.JAIL)), FIRST_SAMPLE.plusMinutes(5));
        String third = recorder.merge(second, Map.of(
                1L, reason(OcDelayReasonEnum.TRAVEL),
                3L, reason(OcDelayReasonEnum.JAIL)), FIRST_SAMPLE.plusMinutes(10));
        String settled = recorder.settle(third, FIRST_SAMPLE.plusMinutes(15));

        assertEquals(List.of(
                        new OcDelayCauseEntry(1L, OcDelayReasonEnum.ITEM, 1430, 0L, 10),
                        new OcDelayCauseEntry(2L, OcDelayReasonEnum.TRAVEL, null, 0L, 10),
                        new OcDelayCauseEntry(3L, OcDelayReasonEnum.JAIL, null, 0L, 10)),
                recorder.decode(settled).stream()
                        .sorted(Comparator.comparingLong(OcDelayCauseEntry::userId))
                        .toList());
    }

    @Test
    @DisplayName("总时长：开放段按段起点到结算时刻计入净阻塞")
    void totalMinutes_shouldIncludeOpenSegment() {
        String open = recorder.merge(null, Map.of(1L, reason(OcDelayReasonEnum.JAIL)), FIRST_SAMPLE);
        OcDelayCauseEntry entry = recorder.decode(open).getFirst();

        assertEquals(20, entry.totalMinutes(FIRST_SAMPLE_MINUTE + 20));
        assertEquals(0, entry.totalMinutes(FIRST_SAMPLE_MINUTE));
    }

    @Test
    @DisplayName("结算：用实际完成时间封闭最后一段，重复结算不再变化")
    void settle_shouldCloseOpenSegmentWithExecutedTime() {
        String open = recorder.merge(null, Map.of(1L, reason(OcDelayReasonEnum.ITEM, 1430)),
                FIRST_SAMPLE);
        String settled = recorder.settle(open, FIRST_SAMPLE.plusMinutes(30));

        assertEquals(List.of(new OcDelayCauseEntry(1L, OcDelayReasonEnum.ITEM, 1430, 0L, 30)),
                recorder.decode(settled));
        assertEquals(settled, recorder.settle(settled, FIRST_SAMPLE.plusMinutes(90)));
    }

    @Test
    @DisplayName("无变化：重复合并同一采样结果时原样返回入参编码供调用方跳过写库")
    void merge_shouldReturnInputWhenNothingChanged() {
        Map<Long, OcDelayReason> samples = Map.of(1L, reason(OcDelayReasonEnum.JAIL));
        String encoded = recorder.merge(null, samples, FIRST_SAMPLE);

        assertEquals(encoded, recorder.merge(encoded, samples, FIRST_SAMPLE.plusMinutes(5)));
        assertNull(recorder.settle(null, FIRST_SAMPLE));
    }

    @Test
    @DisplayName("解码容错：字段缺失、原因未知、数字非法的条目跳过，合法条目保留")
    void decode_shouldSkipInvalidEntries() {
        String delayCause = "1|ITEM|1430|0|30;bad;2|UNKNOWN|0|0|5;3|TRAVEL|abc|0|5;;4|TRAVEL|0|0|60";

        assertEquals(List.of(
                        new OcDelayCauseEntry(1L, OcDelayReasonEnum.ITEM, 1430, 0L, 30),
                        new OcDelayCauseEntry(4L, OcDelayReasonEnum.TRAVEL, null, 0L, 60)),
                recorder.decode(delayCause));
        assertEquals(List.of(), recorder.decode(null));
        assertEquals(List.of(), recorder.decode("   "));
    }

    private static OcDelayReason reason(OcDelayReasonEnum reason) {
        return new OcDelayReason(reason, null);
    }

    private static OcDelayReason reason(OcDelayReasonEnum reason, int itemId) {
        return new OcDelayReason(reason, itemId);
    }
}
