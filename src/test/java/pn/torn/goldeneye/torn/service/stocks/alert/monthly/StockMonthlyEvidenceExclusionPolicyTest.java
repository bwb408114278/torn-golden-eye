package pn.torn.goldeneye.torn.service.stocks.alert.monthly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.service.stocks.alert.monthly.StockMonthlyEvidenceExclusionPolicy.Adjustment;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


/**
 * 月度证据宕机豁免策略纯领域测试 - 保护唯一V2窗口的raw/adjusted排除公式:
 * 全相交29桶435分钟、跨窗口gap 450→15、部分相交只扣实际重叠、V1无排除raw=adjusted、
 * 非法窗口构造fail-fast。
 *
 * @author Bai
 * @version 1.4.8
 * @since 2026.08.26
 */
@DisplayName("月度证据宕机豁免策略测试")
class StockMonthlyEvidenceExclusionPolicyTest {

    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 2, 14, 8, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 2, 14, 15, 15);

    @Test
    @DisplayName("V1版本或不相交范围_无排除_raw与adjusted完全一致")
    void forRuleVersion_v1OrDisjoint_noExclusion() {
        assertTrue(StockMonthlyEvidenceExclusionPolicy.forRuleVersion(
                "PERSONALITY_RULE_V1", "RISK_RULE_V1_SHADOW").isEmpty(), "V1双版本必须返回空策略");
        assertTrue(StockMonthlyEvidenceExclusionPolicy.forRuleVersion(
                StockMonthlyEvidenceExclusionPolicy.V2_PERSONALITY_RULE_VERSION,
                "RISK_RULE_V1_SHADOW").isEmpty(), "版本组合不匹配必须返回空策略");
        assertTrue(StockMonthlyEvidenceExclusionPolicy.forRuleVersion(null, null).isEmpty(),
                "空版本必须返回空策略");

        StockMonthlyEvidenceExclusionPolicy empty =
                StockMonthlyEvidenceExclusionPolicy.forRuleVersion(
                        "PERSONALITY_RULE_V1", "RISK_RULE_V1_SHADOW");
        Adjustment adjustment = empty.adjust(LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 9, 1, 0, 0));
        assertEquals(0L, adjustment.excludedBucketCount(), "空策略不得排除任何桶");
        assertEquals(0L, adjustment.excludedMinutes(), "空策略不得排除任何分钟");
        assertTrue(adjustment.appliedExclusionIds().isEmpty(), "空策略不得记录排除ID");

        StockMonthlyEvidenceExclusionPolicy v2 = v2Policy();
        Adjustment disjoint = v2.adjust(LocalDateTime.of(2026, 3, 1, 0, 0),
                LocalDateTime.of(2026, 9, 1, 0, 0));
        assertEquals(0L, disjoint.excludedBucketCount(), "不相交范围不得排除任何桶");
        assertEquals(0L, disjoint.excludedMinutes(), "不相交范围不得排除任何分钟");
        assertTrue(disjoint.appliedExclusionIds().isEmpty(), "不相交范围不得记录排除ID");
        assertEquals(0L, v2.excludedOverlapMinutes(
                LocalDateTime.of(2026, 3, 1, 0, 0), LocalDateTime.of(2026, 3, 1, 7, 45)),
                "不相交gap不得扣减分钟");
    }

    @Test
    @DisplayName("V2全相交首窗口_29桶435分钟_跨窗口gap 450调整15")
    void v2_fullOverlap_29Buckets435Minutes_gap450To15() {
        StockMonthlyEvidenceExclusionPolicy v2 = v2Policy();
        Adjustment adjustment = v2.adjust(WINDOW_START, WINDOW_END);
        assertEquals(29L, adjustment.excludedBucketCount(), "[08:00,15:15)应相交29个完整15分钟桶");
        assertEquals(435L, adjustment.excludedMinutes(), "[08:00,15:15)应相交435分钟");
        assertEquals(List.of(StockMonthlyEvidenceExclusionPolicy.EXCLUSION_ID),
                adjustment.appliedExclusionIds(), "应记录唯一已审批窗口ID");

        // 跨窗口相邻gap: 前bar 07:45, 后bar 15:15 -> raw 450分钟, 排除重叠[08:00,15:15)=435 -> adjusted 15
        long overlap = v2.excludedOverlapMinutes(
                LocalDateTime.of(2026, 2, 14, 7, 45), LocalDateTime.of(2026, 2, 14, 15, 15));
        assertEquals(435L, overlap, "跨窗口gap应扣减435分钟");
        assertEquals(15L, 450L - overlap, "raw 450扣除后adjusted必须为15");
    }

    @Test
    @DisplayName("V2部分相交_只扣实际相交桶与分钟")
    void v2_partialOverlap_onlyActualOverlapDeducted() {
        StockMonthlyEvidenceExclusionPolicy v2 = v2Policy();
        // 证据区间从窗口内 08:07 开始(非桶对齐边界): 相交分钟 [08:07,15:15)=428,
        // 但完整桶仅 [08:15,15:15) 共 28 个(08:00 桶因 08:07 起点不完整不计)
        Adjustment adjustment = v2.adjust(
                LocalDateTime.of(2026, 2, 14, 8, 7), WINDOW_END);
        assertEquals(28L, adjustment.excludedBucketCount(), "部分相交只计完整落在区间内的桶");
        assertEquals(428L, adjustment.excludedMinutes(), "部分相交只扣实际重叠分钟");
        assertEquals(List.of(StockMonthlyEvidenceExclusionPolicy.EXCLUSION_ID),
                adjustment.appliedExclusionIds());

        // 证据区间只覆盖窗口尾部: [14:30,15:15) 相交45分钟、3个完整桶
        Adjustment tail = v2.adjust(
                LocalDateTime.of(2026, 2, 14, 14, 30), WINDOW_END);
        assertEquals(3L, tail.excludedBucketCount());
        assertEquals(45L, tail.excludedMinutes());

        // 部分相交gap: 前bar 08:10, 后bar 15:30 -> raw 440, 重叠[08:10,15:15)=425 -> adjusted 15
        assertEquals(425L, v2.excludedOverlapMinutes(
                LocalDateTime.of(2026, 2, 14, 8, 10), LocalDateTime.of(2026, 2, 14, 15, 30)));
    }

    @Test
    @DisplayName("非豁免135分钟gap不被扣减")
    void v2_unrelatedGap_notDeducted() {
        StockMonthlyEvidenceExclusionPolicy v2 = v2Policy();
        // 与窗口完全不相邻的135分钟gap: 不得扣减,完整性仍应不完整
        long overlap = v2.excludedOverlapMinutes(
                LocalDateTime.of(2026, 3, 5, 10, 0), LocalDateTime.of(2026, 3, 5, 12, 15));
        assertEquals(0L, overlap, "非豁免gap不得扣减任何分钟");
        assertEquals(135L, 135L - overlap, "135分钟gap调整后仍为135,超过120阈值");
    }

    @Test
    @DisplayName("固定窗口非法边界_非15分钟对齐或start>=end_fail-fast")
    void fixedWindow_illegalBoundary_failFast() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> newWindow(StockMonthlyEvidenceExclusionPolicy.EXCLUSION_ID,
                        LocalDateTime.of(2026, 2, 14, 8, 7), WINDOW_END),
                "非15分钟对齐起点必须fail-fast");
        assertThrows(IllegalArgumentException.class,
                () -> newWindow(StockMonthlyEvidenceExclusionPolicy.EXCLUSION_ID,
                        WINDOW_END, WINDOW_START),
                "start>=end必须fail-fast");
        assertThrows(IllegalArgumentException.class,
                () -> newWindow(StockMonthlyEvidenceExclusionPolicy.EXCLUSION_ID,
                        null, WINDOW_END),
                "空边界必须fail-fast");
    }

    @Test
    @DisplayName("策略常量与计算器V2接线_版本一致")
    void policyConstants_matchCalculatorVersions() {
        assertEquals("PERSONALITY_RULE_V2_OUTAGE_EXCLUSION",
                StockMonthlyStateCalculator.PERSONALITY_RULE_VERSION);
        assertEquals("RISK_RULE_V2_OUTAGE_EXCLUSION",
                StockMonthlyStateCalculator.RISK_RULE_VERSION);
        assertFalse(v2Policy().isEmpty(), "V2双版本必须解析出非空策略");
    }

    /**
     * 构建V2策略实例(经版本解析入口取得,禁止测试直接构造生产策略)。
     *
     * @return V2策略
     */
    private static StockMonthlyEvidenceExclusionPolicy v2Policy() {
        return StockMonthlyEvidenceExclusionPolicy.forRuleVersion(
                StockMonthlyEvidenceExclusionPolicy.V2_PERSONALITY_RULE_VERSION,
                StockMonthlyEvidenceExclusionPolicy.V2_RISK_RULE_VERSION);
    }

    /**
     * 通过反射调用策略类私有嵌套ExclusionWindow构造,触发生产fail-fast校验;
     * 反射包装异常解包后原样抛出构造期异常。
     *
     * @param exclusionId 窗口ID
     * @param start       起点
     * @param end         终点
     * @return 永不返回;非法参数在构造期抛出IllegalArgumentException
     * @throws Exception 反射访问失败或构造校验失败时抛出
     */
    private static Object newWindow(String exclusionId, LocalDateTime start, LocalDateTime end)
            throws Exception {
        Class<?> windowClass = Class.forName(StockMonthlyEvidenceExclusionPolicy.class.getName()
                + "$ExclusionWindow");
        var constructor = windowClass.getDeclaredConstructor(String.class, LocalDateTime.class,
                LocalDateTime.class);
        constructor.setAccessible(true);
        try {
            return constructor.newInstance(exclusionId, start, end);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
