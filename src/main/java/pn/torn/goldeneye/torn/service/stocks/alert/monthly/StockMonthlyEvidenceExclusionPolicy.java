package pn.torn.goldeneye.torn.service.stocks.alert.monthly;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 月度证据已验证宕机豁免策略 - 封装唯一已冻结的V2排除窗口,供月度证据完整性计算
 * 精确扣除已审批不可恢复窗口的15分钟桶与间隔分钟。
 * <p>
 * 本类是版本控制的不可变纯领域策略,不依赖Spring、DAO、时钟、JSON或调度器;
 * 窗口、{@code exclusionId}与V2双规则版本冻结于业务文档
 * {@code .ai/knowledge/stocks/vip_stock_verified_outage_waiver_business_freeze.md}。
 * 未来新增/变更排除窗口必须建立新的V3双规则版本并在本类显式追加窗口,
 * 禁止数据库或配置热更新。
 * <p>
 * 豁免只作用于月度证据完整性的{@code usableBarCoverage}与{@code maxMissingBucketGap}
 * 两个日历指标;价格、趋势、收益、回撤、日收盘、风险投票、成交与实时策略输入
 * 一律不参与排除。
 *
 * @author Bai
 * @version 1.4.8
 * @since 2026.08.26
 */
public final class StockMonthlyEvidenceExclusionPolicy {

    /**
     * 唯一已审批排除窗口ID
     */
    public static final String EXCLUSION_ID = "TORN_MARKET_OUTAGE_20260214_0801_1515";
    /**
     * V2风格规则版本(冻结)
     */
    public static final String V2_PERSONALITY_RULE_VERSION = "PERSONALITY_RULE_V2_OUTAGE_EXCLUSION";
    /**
     * V2风险规则版本(冻结)
     */
    public static final String V2_RISK_RULE_VERSION = "RISK_RULE_V2_OUTAGE_EXCLUSION";

    /**
     * 15分钟bar桶长度(分钟)
     */
    private static final int BUCKET_MINUTES = 15;

    /**
     * 空策略: V1或版本组合不匹配时使用,raw与adjusted完全一致
     */
    private static final StockMonthlyEvidenceExclusionPolicy EMPTY =
            new StockMonthlyEvidenceExclusionPolicy(List.of());
    /**
     * V2策略: 仅承载唯一已冻结的宕机排除窗口
     */
    private static final StockMonthlyEvidenceExclusionPolicy V2 = new StockMonthlyEvidenceExclusionPolicy(
            List.of(new ExclusionWindow(EXCLUSION_ID,
                    LocalDateTime.of(2026, 2, 14, 8, 0),
                    LocalDateTime.of(2026, 2, 14, 15, 15))));

    private final List<ExclusionWindow> windows;

    private StockMonthlyEvidenceExclusionPolicy(List<ExclusionWindow> windows) {
        this.windows = windows;
    }

    /**
     * 按月度双规则版本解析豁免策略。
     * <p>
     * 仅当风格与风险版本同时为V2双版本时返回V2策略;V1或任意其它组合返回空策略,
     * 空策略下raw与adjusted指标必须完全一致。
     *
     * @param personalityRuleVersion 风格规则版本
     * @param riskRuleVersion        风险规则版本
     * @return V2策略或空策略,永不返回null
     */
    public static StockMonthlyEvidenceExclusionPolicy forRuleVersion(String personalityRuleVersion,
                                                                     String riskRuleVersion) {
        if (V2_PERSONALITY_RULE_VERSION.equals(personalityRuleVersion)
                && V2_RISK_RULE_VERSION.equals(riskRuleVersion)) {
            return V2;
        }
        return EMPTY;
    }

    /**
     * 空策略实例,供V1历史兼容调用(三参数计算入口)。
     *
     * @return 空策略
     */
    static StockMonthlyEvidenceExclusionPolicy empty() {
        return EMPTY;
    }

    /**
     * 是否不含任何排除窗口。
     *
     * @return true表示无排除窗口
     */
    public boolean isEmpty() {
        return windows.isEmpty();
    }

    /**
     * 计算证据区间的排除调整量。
     * <p>
     * {@code excludedBucketCount}为已审批窗口与证据区间相交的完整15分钟桶数:
     * 仅统计完整落在证据区间内的窗口桶,窗口与证据边界不对齐时不得按分钟数整除放大。
     *
     * @param evidenceStart 证据起点(含)
     * @param evidenceEnd   证据终点(不含)
     * @return 排除调整量;区间无效或无相交窗口时全部为0
     */
    Adjustment adjust(LocalDateTime evidenceStart, LocalDateTime evidenceEnd) {
        if (evidenceStart == null || evidenceEnd == null || !evidenceStart.isBefore(evidenceEnd)) {
            return new Adjustment(0, 0, List.of());
        }
        long excludedBucketCount = 0;
        long excludedMinutes = 0;
        List<String> appliedIds = new ArrayList<>(windows.size());
        for (ExclusionWindow window : windows) {
            long overlap = overlapMinutes(window, evidenceStart, evidenceEnd);
            if (overlap <= 0) {
                continue;
            }
            excludedBucketCount += containedBucketCount(window, evidenceStart, evidenceEnd);
            excludedMinutes += overlap;
            appliedIds.add(window.exclusionId());
        }
        return new Adjustment(excludedBucketCount, excludedMinutes, List.copyOf(appliedIds));
    }

    /**
     * 计算单个相邻usable bar间隔内可扣除的排除分钟数。
     *
     * @param gapStart 间隔起点(前一根bar开始时间,含)
     * @param gapEnd   间隔终点(后一根bar开始时间,不含)
     * @return 与已审批窗口的实际重叠分钟数;无相交时为0
     */
    long excludedOverlapMinutes(LocalDateTime gapStart, LocalDateTime gapEnd) {
        if (gapStart == null || gapEnd == null || !gapStart.isBefore(gapEnd)) {
            return 0;
        }
        long totalMinutes = 0;
        for (ExclusionWindow window : windows) {
            totalMinutes += overlapMinutes(window, gapStart, gapEnd);
        }
        return totalMinutes;
    }

    /**
     * 计算窗口与任意区间[hostStart, hostEnd)的重叠分钟数。
     *
     * @param window    排除窗口
     * @param hostStart 区间起点(含)
     * @param hostEnd   区间终点(不含)
     * @return 重叠分钟数;无相交时为0
     */
    private static long overlapMinutes(ExclusionWindow window, LocalDateTime hostStart, LocalDateTime hostEnd) {
        LocalDateTime overlapStart = window.start().isAfter(hostStart) ? window.start() : hostStart;
        LocalDateTime overlapEnd = window.end().isBefore(hostEnd) ? window.end() : hostEnd;
        if (!overlapStart.isBefore(overlapEnd)) {
            return 0;
        }
        return Duration.between(overlapStart, overlapEnd).toMinutes();
    }

    /**
     * 统计完整落在证据区间内的窗口15分钟桶数。
     *
     * @param window        排除窗口
     * @param evidenceStart 证据起点(含)
     * @param evidenceEnd   证据终点(不含)
     * @return 完整相交桶数
     */
    private static long containedBucketCount(ExclusionWindow window,
                                             LocalDateTime evidenceStart,
                                             LocalDateTime evidenceEnd) {
        long count = 0;
        for (LocalDateTime bucketStart = window.start(); bucketStart.isBefore(window.end());
             bucketStart = bucketStart.plusMinutes(BUCKET_MINUTES)) {
            LocalDateTime bucketEnd = bucketStart.plusMinutes(BUCKET_MINUTES);
            if (!bucketStart.isBefore(evidenceStart) && !bucketEnd.isAfter(evidenceEnd)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 已审批排除窗口 - 15分钟对齐的不可变固定窗口,构造期fail-fast校验。
     *
     * @param exclusionId 窗口唯一ID
     * @param start       窗口起点(含)
     * @param end         窗口终点(不含)
     */
    private record ExclusionWindow(String exclusionId, LocalDateTime start, LocalDateTime end) {
        ExclusionWindow {
            if (start == null || end == null || !start.isBefore(end)) {
                throw new IllegalArgumentException("排除窗口要求start<end: " + exclusionId);
            }
            if (!isBucketAligned(start) || !isBucketAligned(end)) {
                throw new IllegalArgumentException("排除窗口必须15分钟对齐: " + exclusionId);
            }
        }

        private static boolean isBucketAligned(LocalDateTime time) {
            return time.getMinute() % BUCKET_MINUTES == 0 && time.getSecond() == 0 && time.getNano() == 0;
        }
    }

    /**
     * 证据区间排除调整量 - 不可变计算结果。
     *
     * @param excludedBucketCount 相交的完整15分钟桶数
     * @param excludedMinutes     相交分钟数
     * @param appliedExclusionIds 实际相交的排除窗口ID(不可变、有序)
     */
    record Adjustment(long excludedBucketCount, long excludedMinutes, List<String> appliedExclusionIds) {
    }
}
