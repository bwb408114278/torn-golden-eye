package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric;

import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;

/**
 * OC产出估算策略。
 *
 * <p>把「一个OC」折算成期望净产出与期望人天。两种来源（实测与等级映射）产出同一量纲，
 * 由注册表按样本量做可信度加权，调用方只看到唯一一套评分公式。
 * 实现类必须只做估算，不写库、不读系统时间（时间与团队人数都由{@link OcYieldContext}传入）。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
public interface OcYieldEstimator {
    /**
     * 判断该实现是否承接指定估算来源。
     *
     * @param source 估算来源
     * @return 承接时返回true
     */
    boolean supports(OcYieldSourceEnum source);

    /**
     * 估算指定OC（或其所属整条链）的期望产出。
     *
     * @param oc      OC数据
     * @param context 本次估算的只读上下文，提供时间基准与每支队的实际岗位数
     * @return 期望产出估算值
     */
    OcYieldEstimate estimate(TornFactionOcDO oc, OcYieldContext context);
}
