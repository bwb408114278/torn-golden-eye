package pn.torn.goldeneye.torn.service.faction.oc.recommend.rank;

import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendOutcomeEnum;

import java.math.BigDecimal;

/**
 * 一个候选岗位的推荐评分快照。
 *
 * <p>一个候选岗位的推荐评分快照。R6评分 = 单位人天产出 × 开动紧迫度，负责跨OC经济比较；
 * 完整推荐还由OcRecommendRanker按最高胜任等级、核心/大众和同OC ΔV排序。
 * displayRating只在完整排序后生成，与最终顺序单调不减。</p>
 *
 * <p>两个R6因子都恒为非负，DAG负ΔV也不会导致候选被过滤。替代概率只用于稀缺度展示。</p>
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
 * @param displayRating         完整排序后的百分制展示分；未计算时回退R6货币分换算
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.25
 */
public record OcRecommendScore(
        BigDecimal unitMemberDayValue,
        BigDecimal startUrgency,
        BigDecimal score,
        BigDecimal substituteProbability,
        Integer substituteCount,
        Integer displayRating) {

    /**
     * 构造尚未分组的R6评分。
     */
    public OcRecommendScore(BigDecimal unitMemberDayValue, BigDecimal startUrgency, BigDecimal score,
                            BigDecimal substituteProbability, Integer substituteCount) {
        this(unitMemberDayValue, startUrgency, score, substituteProbability, substituteCount, null);
    }

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
     * <p>完整排序的分组、等级、核心/大众和R6共同保证展示分与最终顺序单调不减，整数四舍五入允许同分。</p>
     *
     * @return 百分制评分，取值1至100
     */
    public int rating() {
        return displayRating == null ? OcRecommendRating.ratingOf(score) : displayRating;
    }

    /**
     * 写入完整排序对应的展示分，不改变R6经济评分。
     *
     * @param rating 百分制展示分
     * @return 新评分快照
     */
    public OcRecommendScore withDisplayRating(int rating) {
        return new OcRecommendScore(unitMemberDayValue, startUrgency, score,
                substituteProbability, substituteCount, rating);
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
