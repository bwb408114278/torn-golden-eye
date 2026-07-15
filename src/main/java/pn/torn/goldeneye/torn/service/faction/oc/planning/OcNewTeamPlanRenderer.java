package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcNewTeamPlan;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanBranch;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshAdvice;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamPlan;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 将结构化规划结果渲染为可直接执行的群消息。
 */
@Component
public class OcNewTeamPlanRenderer {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public String render(OcNewTeamPlan plan) {
        OcPlanBranch branch = plan.recommendedBranch();
        StringBuilder result = new StringBuilder("【OC新队#")
                .append(branch.mode().getCommand()).append("】\n")
                .append("快照时间: ").append(plan.snapshotTime().format(TIME_FORMAT)).append('\n')
                .append("高阶链: 已承诺").append(branch.chainCapacity().committedCount())
                .append("条，已证明安全并行").append(branch.chainCapacity().provenSafeConcurrentCount())
                .append("条，最多可新增").append(branch.chainCapacity().provenAdditionalCount())
                .append("条，本方案建议新增").append(branch.recommendedAdditionalChains()).append("条");
        if (!branch.chainCapacity().maximumProven()) {
            result.append("（仅为已证明下界）");
        }
        if (branch.chainCapacity().committedCount() > 0
                || branch.chainCapacity().provenSafeConcurrentCount() > 0) {
            result.append("\n说明: 高阶链容量已计入后继资源预留；前置成功后请重新运行本命令");
        }
        appendPlans(result, "旧队补位", branch.existingTeamPlans());
        appendPlans(result, "新队启动", branch.newTeamPlans());
        appendRefreshAdvice(result, branch.refreshAdvice());
        appendWarnings(result, plan.catalogWarnings(), branch.warnings());
        result.append("\n\n其他分支: ");
        plan.alternatives().forEach(alternative -> result.append("OC新队#")
                .append(alternative.mode().getCommand()).append("；"));
        return result.toString();
    }

    private void appendPlans(StringBuilder result, String title, List<OcTeamPlan> plans) {
        result.append("\n\n【").append(title).append("】");
        List<OcTeamPlan> actionable = plans.stream()
                .filter(plan -> !plan.assignments().isEmpty() || !plan.complete()
                        || plan.note().contains("OBSERVE_ONLY"))
                .toList();
        if (actionable.isEmpty()) {
            result.append("\n无立即动作");
            return;
        }
        int index = 1;
        for (OcTeamPlan team : actionable) {
            result.append('\n').append(index++).append(". ").append(team.rank()).append("级 ")
                    .append(team.ocName()).append(" #").append(team.ocId())
                    .append("\n   ").append(team.note());
            for (OcPlannedAssignment assignment : team.assignments()) {
                result.append("\n   - ").append(assignment.joinAt().format(TIME_FORMAT)).append(' ')
                        .append(assignment.nickname()).append(" → ").append(assignment.slotCode())
                        .append("（").append(assignment.passRate()).append("%，门槛")
                        .append(assignment.requiredPassRate()).append("%）");
            }
            if (team.completionAt() != null) {
                result.append("\n   预计完成: ").append(team.completionAt().format(TIME_FORMAT));
            }
        }
    }

    private void appendRefreshAdvice(StringBuilder result, OcRefreshAdvice advice) {
        result.append("\n\n【刷新建议】\n").append(advice.reason());
        if (advice.refreshRecommended()) {
            result.append("\n按钮池: ").append(advice.spawnPool())
                    .append("，次数: ").append(advice.refreshCount());
            if (advice.replanAfterRefresh()) {
                result.append("；刷新后重新执行当前二级指令");
            }
        }
    }

    private void appendWarnings(StringBuilder result, List<String> catalogWarnings,
                                List<String> branchWarnings) {
        if (catalogWarnings.isEmpty() && branchWarnings.isEmpty()) {
            return;
        }
        result.append("\n\n【配置/求解警告】");
        java.util.stream.Stream.concat(catalogWarnings.stream(), branchWarnings.stream())
                .distinct().forEach(warning -> result.append("\n- ").append(warning));
    }
}
