package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendScore;

import java.math.BigDecimal;

/**
 * 待排序的推荐候选（一个OC的一个空位）。
 *
 * <p>不可变模型：排序过程中只替换本记录，不再就地改写共享的{@link TornFactionOcDO}或
 * {@link TornFactionOcSlotDO}，避免同一请求内其它读取被污染。同一OC的多个空位各自独立成一条候选，
 * 不做OC级去重。</p>
 *
 * @param oc            候选OC
 * @param slot          候选空位
 * @param requirement   岗位配置（已按帮派覆盖成功率要求），为空时该候选不可推荐
 * @param passRate      成员在该岗位的实测成功率；配置了门槛时null表示无实测记录、不可填
 * @param estimate      该OC（或整条链）的期望产出估算值
 * @param score         推荐评分，排序键本体；尚未计算时为null
 * @param marginalValue 成员相对标尺替补的DAG期望收益增量；无图或奖励不全时为null，不参与ΔV决胜
 * @author Bai
 * @version 1.9.6
 * @since 2026.10.23
 */
public record OcRecommendCandidate(
        TornFactionOcDO oc,
        TornFactionOcSlotDO slot,
        TornSettingOcSlotDO requirement,
        Integer passRate,
        OcYieldEstimate estimate,
        OcRecommendScore score,
        BigDecimal marginalValue) {

    /**
     * 兼容尚未计算DAG边际的候选构建。
     */
    public OcRecommendCandidate(TornFactionOcDO oc, TornFactionOcSlotDO slot,
                                TornSettingOcSlotDO requirement, Integer passRate,
                                OcYieldEstimate estimate, OcRecommendScore score) {
        this(oc, slot, requirement, passRate, estimate, score, null);
    }

    /**
     * 复制候选并替换推荐评分。
     *
     * @param newScore 新的推荐评分
     * @return 替换后的候选
     */
    public OcRecommendCandidate withScore(OcRecommendScore newScore) {
        return new OcRecommendCandidate(oc, slot, requirement, passRate, estimate, newScore, marginalValue);
    }

    /**
     * 复制候选并写入DAG边际收益。
     *
     * @param newMarginalValue 边际收益；null表示不参与ΔV决胜
     * @return 写入边际后的候选
     */
    public OcRecommendCandidate withMarginalValue(BigDecimal newMarginalValue) {
        return new OcRecommendCandidate(oc, slot, requirement, passRate, estimate, score, newMarginalValue);
    }
}
