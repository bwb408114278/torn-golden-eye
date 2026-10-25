package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendOutcomeEnum;

import java.math.BigDecimal;

/**
 * 一个候选岗位的推荐评分快照。
 *
 * <p>评分 = 单位人天产出 × 开动紧迫度。单位人天产出沿用度量层口径，是「每占用一名成员一天，
 * 帮派期望产出多少货币」（元/人天，只做排序内核，对外由{@link OcRecommendRating}换算成百分制）；
 * 开动紧迫度是0.10至1.00的
 * 时间折扣，越早开动越接近1。两者都恒为非负，因此评分恒为非负，不存在负分展示，
 * 也<b>不存在「评分不为正就不推荐」的过滤</b>：只要还有未填的空位就一定有候选，
 * 只有完全没有可填空位时推荐才为空。</p>
 *
 * <p>两个因子都对外可解释：单位人天产出说明这支队值多少，开动紧迫度说明还要等多久，
 * 用户据此就能看懂「为什么推这支队、为什么高手也可能先补一支低等级但马上开动的队」。</p>
 *
 * <p>替代概率不参与评分：本帮派绝大多数岗位的可填人数远多于空位数，该值恒为1，
 * 乘进评分只会把收益项整体抹成0（历史缺陷）。它只作为稀缺度分档用于解释展示。</p>
 *
 * @param unitMemberDayValue    单位人天产出，期望净产出除以期望人天，货币单位（元/人天）
 * @param startUrgency          开动紧迫度，取值0.10至1.00
 * @param score                 推荐评分，单位人天产出乘以开动紧迫度，排序键
 * @param substituteProbability 替代概率，null表示未知，仅用于稀缺度分档
 * @param substituteCount       除本人之外可填该岗位的人数，null表示未知
 * @author Bai
 * @version 1.9.4
 * @since 2026.10.25
 */
public record OcRecommendScore(
        BigDecimal unitMemberDayValue,
        BigDecimal startUrgency,
        BigDecimal score,
        BigDecimal substituteProbability,
        Integer substituteCount) {

    /**
     * 归一化空值，保证评分与因子永远可用于比较。
     */
    public OcRecommendScore {
        unitMemberDayValue = unitMemberDayValue == null ? BigDecimal.ZERO : unitMemberDayValue;
        startUrgency = startUrgency == null ? BigDecimal.ZERO : startUrgency;
        score = score == null ? BigDecimal.ZERO : score;
    }

    /**
     * 把货币量纲的排序键换算成对外展示的百分制评分。
     *
     * <p>对内排序仍用货币量纲，对外只展示0至100的评分：两者严格单调，因此不会出现
     * 「评分高但排在后面」。换算口径见{@link OcRecommendRating}。</p>
     *
     * @return 百分制评分，取值1至100
     */
    public int rating() {
        return OcRecommendRating.ratingOf(score);
    }

    /**
     * 按替代概率映射稀缺度分档，仅用于对外解释，不参与排序。
     *
     * @return 稀缺度分档；替代概率未知时为未知分档
     */
    public OcRecommendOutcomeEnum outcome() {
        return OcRecommendOutcomeEnum.fromSubstituteProbability(substituteProbability);
    }
}
