package pn.torn.goldeneye.torn.service.stocks.alert.monthly;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 已备案停机窗口豁免 - 对月度证据日历指标(桶覆盖率/最大间隔)做最小豁免的纯静态领域类。
 * <p>
 * 背景: 2026-02-14 07:45→15:15 的一次性已备案停机(间隔450分钟,超过120分钟阈值)落在
 * 365天证据窗口内,若不豁免会污染其后约12个月的完整性判定,导致每月complete=false、
 * 无法自动确认、私聊指令全量停推(1.8.0 Review P0-1,用户裁决采用方案a)。
 * <p>
 * 冻结口径:
 * <ul>
 *   <li>豁免只作用于 {@code usableBarCoverage} 与 {@code maxMissingBucketGap} 的adjusted口径;
 *       价格、趋势、收益、回撤、日收盘、月均、风险投票、成交一律不参与豁免;</li>
 *   <li>{@code excludedBucketCount} 只统计完整落在证据区间内的窗口15分钟对齐桶,
 *       窗口与证据边界不对齐时不得按分钟整除放大;</li>
 *   <li>未备案的新停机仍然阻断(fail-closed不放松);新增窗口必须显式追加常量并记录版本,
 *       禁止DB/配置热更新;</li>
 *   <li>窗口定义fail-fast: 非15分钟对齐或start&ge;end在类加载时直接抛异常。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.8.0
 * @since 2026.10.04
 */
public final class StockMonthlyOutageWaiver {

    /**
     * 备案停机窗口唯一标识(2026-02-14 Torn市场停机,实际停机08:01–15:15,
     * 窗口按15分钟桶边界对齐取[08:00,15:15))
     */
    static final String EXCLUSION_ID = "TORN_MARKET_OUTAGE_20260214_0801_1515";

    /**
     * 窗口起点(15分钟对齐,含)
     */
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 2, 14, 8, 0);

    /**
     * 窗口终点(15分钟对齐,不含)
     */
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 2, 14, 15, 15);

    static {
        validateWindowDefinition();
    }

    private StockMonthlyOutageWaiver() {
    }

    /**
     * 计算证据区间相对备案窗口的豁免调整量。
     *
     * @param evidenceStart 证据起点(可为null)
     * @param evidenceEnd   证据终点(可为null)
     * @return 豁免调整量(证据区间与窗口无相交时为零值+空ID列表)
     */
    static Adjustment adjust(LocalDateTime evidenceStart, LocalDateTime evidenceEnd) {
        long overlapMinutes = excludedOverlapMinutes(evidenceStart, evidenceEnd);
        if (overlapMinutes <= 0) {
            return new Adjustment(0L, 0L, List.of());
        }
        LocalDateTime effectiveStart = max(evidenceStart, WINDOW_START);
        long startOffsetMinutes = Duration.between(WINDOW_START, effectiveStart).toMinutes();
        long endOffsetMinutes = startOffsetMinutes + overlapMinutes;
        // 只统计完整落在证据区间内的窗口对齐桶: 起点向上取整到桶边界,终点向下取整
        long firstFullBucket = ceilDiv(startOffsetMinutes, 15L);
        long lastFullBucket = endOffsetMinutes / 15L;
        long fullBucketCount = Math.max(lastFullBucket - firstFullBucket, 0L);
        return new Adjustment(fullBucketCount, overlapMinutes, List.of(EXCLUSION_ID));
    }

    /**
     * 计算相邻可用bar间隔与备案窗口的真实重叠分钟数。
     *
     * @param gapStart 间隔起点(前一可用bar时间,可为null)
     * @param gapEnd   间隔终点(后一可用bar时间,可为null)
     * @return 重叠分钟数(无相交时为0)
     */
    static long excludedOverlapMinutes(LocalDateTime gapStart, LocalDateTime gapEnd) {
        if (gapStart == null || gapEnd == null) {
            return 0L;
        }
        LocalDateTime overlapStart = max(gapStart, WINDOW_START);
        LocalDateTime overlapEnd = min(gapEnd, WINDOW_END);
        if (!overlapStart.isBefore(overlapEnd)) {
            return 0L;
        }
        return Duration.between(overlapStart, overlapEnd).toMinutes();
    }

    /**
     * 类加载时校验窗口定义(15分钟对齐且起止有序),违反直接快速失败,
     * 防止后续新增窗口时把非法常量带入生产计算。
     */
    private static void validateWindowDefinition() {
        if (!isAlignedToBucket(WINDOW_START) || !isAlignedToBucket(WINDOW_END)
                || !WINDOW_START.isBefore(WINDOW_END)) {
            throw new IllegalStateException("备案停机窗口定义非法: start=" + WINDOW_START
                    + ", end=" + WINDOW_END + "(要求15分钟对齐且start<end)");
        }
    }

    /**
     * 判断时点是否15分钟桶对齐(分秒纳秒均为0的倍数15分)。
     *
     * @param time 待校验时点
     * @return true表示对齐
     */
    private static boolean isAlignedToBucket(LocalDateTime time) {
        return time.getNano() == 0 && time.getSecond() == 0 && time.getMinute() % 15 == 0;
    }

    /**
     * 取较晚时点。
     */
    private static LocalDateTime max(LocalDateTime left, LocalDateTime right) {
        return left.isAfter(right) ? left : right;
    }

    /**
     * 取较早时点。
     */
    private static LocalDateTime min(LocalDateTime left, LocalDateTime right) {
        return left.isBefore(right) ? left : right;
    }

    /**
     * 向上取整除法。
     */
    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    /**
     * 豁免调整量。
     *
     * @param excludedBucketCount 完整落在证据区间内的豁免桶数
     * @param excludedMinutes     证据区间与窗口的重叠分钟数
     * @param appliedExclusionIds 实际相交的豁免窗口ID列表(无相交时为空)
     */
    record Adjustment(long excludedBucketCount,
                      long excludedMinutes,
                      List<String> appliedExclusionIds) {
    }
}
