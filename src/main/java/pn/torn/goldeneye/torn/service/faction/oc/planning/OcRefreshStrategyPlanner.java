package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshAdvice;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamPlan;

import java.util.List;

/**
 * 根据已证明的可执行计划给出一次刷新建议。
 */
@Component
public class OcRefreshStrategyPlanner {
    /**
     * 根据规划模式、当前可执行队伍和高阶容量生成刷新建议。
     *
     * @param mode 规划模式
     * @param newTeams 当前快照可执行的新队方案
     * @param chainCapacity 高阶链安全容量证明
     * @return 不预测随机刷新结果的可解释刷新建议
     */
    public OcRefreshAdvice plan(OcPlanMode mode, List<OcTeamPlan> newTeams,
                                OcSafeChainCapacityResult chainCapacity) {
        if (!newTeams.isEmpty()) {
            return new OcRefreshAdvice(false, "NONE", 0, false,
                    "当前已有可启动OC，先执行人员加入计划，完成后再重新规划");
        }
        boolean highChain = chainCapacity.provenAdditionalCount() > 0
                && !OcPlanMode.CONSERVATIVE.equals(mode);
        if (highChain) {
            return new OcRefreshAdvice(true, "HIGH_CHAIN_ROOT", 1, true,
                    "当前仍有已证明安全的高阶链容量，建议高阶按钮刷新1次后重新执行命令");
        }
        return new OcRefreshAdvice(true, "NORMAL_7_8", 1, true,
                "当前没有可启动的普通空OC，建议7/8按钮刷新1次后重新执行命令");
    }
}
