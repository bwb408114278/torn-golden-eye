package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendOutcomeEnum;

import java.math.BigDecimal;

/**
 * 一个候选岗位的机会成本快照。
 *
 * <p>机会成本 = 期望净产出 × (1 − 替代概率) + 被锁死人天 × 每日等待成本
 * − 我的占用人天 × 每日等待成本 × 占用成本系数。它同时涵盖「高手去关键岗位」
 * 「停转队优先」「剩余空位少优先」，因此是唯一排序键，不再有第二套评分公式。</p>
 *
 * <p>替代概率为null表示未知（该岗位在成功率矩阵中无样本），此时第一项按
 * 「没有任何人能替代我」全额计入，属于中性偏保守的一侧。</p>
 *
 * @param expectedNetReward     期望净产出，货币单位
 * @param substituteProbability 替代概率，null表示未知
 * @param substituteCount       除本人之外可填该岗位的人数，null表示未知
 * @param lockedMemberDays      被锁死人天，只统计既有进度的成员
 * @param occupiedMemberDays    本人预计占用的人天
 * @param opportunityCost       机会成本本体，排序键
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
public record OcOpportunityCost(BigDecimal expectedNetReward,
                                BigDecimal substituteProbability,
                                Integer substituteCount,
                                BigDecimal lockedMemberDays,
                                BigDecimal occupiedMemberDays,
                                BigDecimal opportunityCost) {

    /**
     * 按替代概率映射稀缺度分档，仅用于对外解释。
     *
     * @return 稀缺度分档；替代概率未知时为未知分档
     */
    public OcRecommendOutcomeEnum outcome() {
        return OcRecommendOutcomeEnum.fromSubstituteProbability(substituteProbability);
    }
}
