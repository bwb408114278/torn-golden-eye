package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 单个OC（或整条链）的期望产出估算值。
 *
 * <p>口径：期望净产出为「奖励（现金加道具价值）乘以链上各步成功率再减去预期道具成本」的帮派口径期望值；
 * 期望人天为链上各步「人数乘以单岗位期望占用天数」之和。两者共同构成唯一排序量纲
 * 「期望净产出 / 期望人天」，不区分是否在大锅饭名单内。</p>
 *
 * @param expectedNetReward  期望净产出，货币单位
 * @param expectedMemberDays 期望占用总人天；为0表示无法估算占用
 * @param source             估算来源，说明该值由实测还是映射得出
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
public record OcYieldEstimate(BigDecimal expectedNetReward,
                              BigDecimal expectedMemberDays,
                              OcYieldSourceEnum source) {
    /**
     * 单位人天产出的小数位数，用于展示与排序比较的口径统一。
     */
    private static final int PER_MEMBER_DAY_SCALE = 2;

    /**
     * 归一化空值，避免调用方在排序时做空判断。
     */
    public OcYieldEstimate {
        expectedNetReward = expectedNetReward == null ? BigDecimal.ZERO : expectedNetReward;
        expectedMemberDays = expectedMemberDays == null ? BigDecimal.ZERO : expectedMemberDays;
    }

    /**
     * 构造无产出、无人天的空估算值。
     *
     * @param source 估算来源
     * @return 期望净产出与期望人天均为0的估算值
     */
    public static OcYieldEstimate zero(OcYieldSourceEnum source) {
        return new OcYieldEstimate(BigDecimal.ZERO, BigDecimal.ZERO, source);
    }

    /**
     * 计算单位人天产出，即唯一排序量纲「期望净产出 / 期望人天」。
     *
     * @return 单位人天产出；期望人天不大于0时返回0，避免除零
     */
    public BigDecimal netPerMemberDay() {
        if (expectedMemberDays.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return expectedNetReward.divide(expectedMemberDays, PER_MEMBER_DAY_SCALE, RoundingMode.HALF_UP);
    }
}
