package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPauseAssessment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;

import java.time.Duration;
import java.util.List;

/**
 * 模式停转政策评估器。对每条候选时间线计算新增停转是否符合当前模式，
 * 不把既有停转误算为本次制造。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public class OcPausePolicyEvaluator {

    /**
     * 判断一组停转评估是否符合指定模式的停转政策。供时间线事件推进器在产生
     * 候选状态时逐分支消费：不符合政策的候选状态直接淘汰。
     *
     * <p>保守模式不允许任何主动新增停转；均衡单次不超过6小时；收益单次不超过12小时。
     * 已发生停转不计为新增。</p>
     *
     * @param pauses 候选时间线的停转评估列表
     * @param mode   规划模式
     * @return 全部新增停转均在模式上限内时返回true
     */
    public boolean withinPolicy(List<OcPauseAssessment> pauses, OcPlanMode mode) {
        Duration maxAllowed = OcTimelinePolicy.maxNewPause(mode);
        return pauses.stream()
                .filter(pause -> !pause.preExistingPause())
                .allMatch(pause -> pause.newPauseDuration().compareTo(maxAllowed) <= 0);
    }

    /**
     * 判断候选时间线是否选择停转超过保守上限但不超过均衡上限。
     *
     * @param maxNewPause 候选时间线最大单次新增停转时长
     * @return 需要均衡级停转容忍时返回true
     */
    public boolean requiresBalancedTier(Duration maxNewPause) {
        return maxNewPause != null && !maxNewPause.isZero()
                && maxNewPause.compareTo(OcTimelinePolicy.BALANCED_MAX_NEW_PAUSE) <= 0;
    }

    /**
     * 判断候选时间线是否选择停转超过均衡上限但不超过收益上限。
     *
     * @param maxNewPause 候选时间线最大单次新增停转时长
     * @return 需要收益级停转容忍时返回true
     */
    public boolean requiresProfitTier(Duration maxNewPause) {
        return maxNewPause != null
                && maxNewPause.compareTo(OcTimelinePolicy.BALANCED_MAX_NEW_PAUSE) > 0
                && maxNewPause.compareTo(OcTimelinePolicy.PROFIT_MAX_NEW_PAUSE) <= 0;
    }
}
