package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric;

import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;

import java.time.LocalDateTime;

/**
 * OC产出估算策略。
 *
 * <p>把「一个OC」折算成期望净产出与期望人天。上层不再区分名单内与名单外：名单内有实测就走实测，
 * 名单外没有历史就走等级映射，两种来源产出同一量纲，因此不需要加权融合，也不需要第二套评分公式。
 * 实现类必须只做估算，不写库、不读系统时间（时间由调用方传入）。</p>
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
     * @param oc           OC数据
     * @param snapshotTime 本次推荐的时间基准
     * @return 期望产出估算值
     */
    OcYieldEstimate estimate(TornFactionOcDO oc, LocalDateTime snapshotTime);
}
