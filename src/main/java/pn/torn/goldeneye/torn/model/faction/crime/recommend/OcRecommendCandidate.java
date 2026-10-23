package pn.torn.goldeneye.torn.model.faction.crime.recommend;

import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcOpportunityCost;

/**
 * 待排序的推荐候选（一个OC的一个空位）。
 *
 * <p>不可变模型：排序过程中只替换本记录，不再就地改写共享的{@link TornFactionOcDO}或
 * {@link TornFactionOcSlotDO}，避免同一请求内其它读取被污染。同一OC的多个空位各自独立成一条候选，
 * 不做OC级去重。</p>
 *
 * @param oc          候选OC
 * @param slot        候选空位
 * @param requirement 岗位配置（已按帮派覆盖成功率要求），为空时该候选不可推荐
 * @param passRate    成员在该岗位的成功率；null表示成功率矩阵无记录，按能力未知处理
 * @param estimate    该OC（或整条链）的期望产出估算值
 * @param cost        机会成本，排序键本体；尚未计算时为null
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
public record OcRecommendCandidate(TornFactionOcDO oc,
                                   TornFactionOcSlotDO slot,
                                   TornSettingOcSlotDO requirement,
                                   Integer passRate,
                                   OcYieldEstimate estimate,
                                   OcOpportunityCost cost) {

    /**
     * 复制候选并替换机会成本。
     *
     * @param newCost 新的机会成本
     * @return 替换后的候选
     */
    public OcRecommendCandidate withCost(OcOpportunityCost newCost) {
        return new OcRecommendCandidate(oc, slot, requirement, passRate, estimate, newCost);
    }
}
