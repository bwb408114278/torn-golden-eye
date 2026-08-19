package pn.torn.goldeneye.repository.model.faction.oc;

import java.math.BigDecimal;

/**
 * 按OC等级和名称聚合的只读收益统计DTO。仅服务于OC规划价值证据，不是实体表DO。
 *
 * @param rank                     OC等级
 * @param ocName                   OC名称
 * @param attemptCount             已完成状态的历史尝试次数（成功与失败均计入）
 * @param successCount             成功完成次数
 * @param rewardCompleteCount      奖励数据完整的成功样本数
 * @param totalReward              奖励完整样本的收益合计，口径为reward_money加奖励物品价值之和
 * @param observedRewardPerAttempt 观察每次尝试收益，失败按0计入；无完整样本时为null
 * @param rewardFloor              奖励完整样本中的最小收益，作为可靠收益下界；无完整样本时为null
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcPlanningRewardStatsDO(
        int rank,
        String ocName,
        int attemptCount,
        int successCount,
        int rewardCompleteCount,
        long totalReward,
        BigDecimal observedRewardPerAttempt,
        Long rewardFloor) {

    /**
     * 构造无有效奖励样本的空统计。
     *
     * @param rank         OC等级
     * @param ocName       OC名称
     * @param attemptCount 已完成状态的历史尝试次数
     * @param successCount 成功完成次数
     * @return 奖励字段为空的统计
     */
    public static OcPlanningRewardStatsDO empty(int rank, String ocName,
                                                int attemptCount, int successCount) {
        return new OcPlanningRewardStatsDO(rank, ocName, attemptCount, successCount,
                0, 0L, null, null);
    }
}
