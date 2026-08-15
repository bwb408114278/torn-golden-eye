package pn.torn.goldeneye.torn.service.faction.oc.planning.api;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcCurrentOccupancySummary;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshInstructionPlan;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRiskFlagEnum;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelinePolicy;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 将匿名时间线求解结果渲染为OC指挥官可执行的刷新指令。
 *
 * <p>始终匿名：不输出成员、ID、岗位、个人加入或释放时间和链内部运行明细。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@Component
public class OcNewTeamPlanRenderer {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /**
     * 渲染刷新操作指令，不输出成员、岗位或加入时间。
     *
     * @param plan 刷新操作指令
     * @return 群消息文本
     */
    public String render(OcRefreshInstructionPlan plan) {
        StringBuilder result = new StringBuilder("【OC新队#")
                .append(plan.mode().getCommand()).append("】\n")
                .append("快照时间: ").append(plan.snapshotTime().format(TIME_FORMAT));
        appendCurrentOccupancy(result, plan.occupancySummary());
        appendInstruction(result, plan);
        appendTimeline(result, plan);
        appendWarnings(result, plan);
        return result.toString();
    }

    /**
     * 追加当前现实OC和达标成员占用摘要。
     *
     * @param result  输出缓冲区
     * @param summary 当前现实占用摘要
     */
    private void appendCurrentOccupancy(StringBuilder result,
                                        OcCurrentOccupancySummary summary) {
        result.append("\n\n【当前OC占用】")
                .append("\n- 当前队伍: ").append(summary.currentTeamCount()).append("个")
                .append("（已有人").append(summary.joinedTeamCount())
                .append("个 / 无人").append(summary.emptyTeamCount()).append("个）")
                .append("\n- 实际占用成员: ").append(summary.occupiedMemberCount()).append("人")
                .append("\n- 达标成员: ").append(summary.qualifiedMemberCount()).append("人")
                .append("\n- 已占用达标成员: ")
                .append(summary.occupiedQualifiedMemberCount()).append("人")
                .append("\n- 空闲达标成员: ")
                .append(summary.idleQualifiedMemberCount()).append("人");
    }

    /**
     * 追加刷新指令与匿名原因说明。
     *
     * @param result 输出缓冲区
     * @param plan   刷新操作指令
     */
    private void appendInstruction(StringBuilder result, OcRefreshInstructionPlan plan) {
        result.append("\n\n【刷新指令】");
        if (plan.normalRefreshCount() == 0 && plan.highRefreshCount() == 0) {
            result.append("\n暂不刷新");
        } else {
            if (plan.normalRefreshCount() > 0) {
                result.append("\n- 普通池: 刷新").append(plan.normalRefreshCount()).append("次");
            }
            if (plan.highRefreshCount() > 0) {
                result.append("\n- 高阶池: 刷新").append(plan.highRefreshCount()).append("次");
            }
            result.append("\n- 完成后重新运行 OC新队#").append(plan.mode().getCommand());
        }
        result.append("\n说明: ").append(plan.reason());
        if (plan.lowerBound()) {
            result.append("；建议次数为当前时间预算内已证明安全下界");
        }
        if (plan.riskFlags().contains(OcRiskFlagEnum.ECONOMIC_EVIDENCE_INSUFFICIENT)) {
            result.append("；收益证据不足，未据此提高刷新或停转建议");
        }
    }

    /**
     * 追加匿名时间线评估：状态维度、流动性、停转和重新评估窗口。
     *
     * @param result 输出缓冲区
     * @param plan   刷新操作指令
     */
    private void appendTimeline(StringBuilder result, OcRefreshInstructionPlan plan) {
        result.append("\n\n【时间线评估】")
                .append("\n- 配置状态: ").append(configurationText(plan))
                .append("；证明状态: ").append(proofText(plan));
        if (plan.riskFlags().contains(OcRiskFlagEnum.DEADLOCK_RISK)) {
            result.append("\n- 当前存在全帮卡死或被迫拆队风险（本次规划窗口内）");
        } else {
            result.append("\n- 当前不存在被迫拆队风险");
        }
        if (plan.nextCriticalReleaseAt() != null) {
            result.append("\n- 下一批关键成员预计 ")
                    .append(plan.nextCriticalReleaseAt().format(TIME_FORMAT)).append(" 释放");
        } else {
            result.append("\n- 当前无可证明的成员释放事件");
        }
        appendPauseText(result, plan);
        result.append("\n- 建议重新评估窗口: ")
                .append(plan.replanWindow().nextReplanAt().format(TIME_FORMAT))
                .append(" ～ ")
                .append(plan.replanWindow().latestReplanAt().format(TIME_FORMAT)).append("前");
        List<String> reasonTexts = new ArrayList<>();
        plan.reasonCodes().forEach(code -> reasonTexts.add(code.getDescription()));
        if (!reasonTexts.isEmpty()) {
            result.append("\n- 原因: ").append(String.join("；", reasonTexts));
        }
        result.append("\n- 若已执行刷新或随机结果发生变化，应立即重新运行指令");
    }

    /**
     * 生成配置状态文案。
     *
     * @param plan 刷新操作指令
     * @return 配置状态文案
     */
    private String configurationText(OcRefreshInstructionPlan plan) {
        return switch (plan.configurationStatus()) {
            case VALID -> "有效";
            case INVALID -> "无效";
            case INCOMPLETE -> "未配置完整";
        };
    }

    /**
     * 生成证明状态文案。
     *
     * @param plan 刷新操作指令
     * @return 证明状态文案
     */
    private String proofText(OcRefreshInstructionPlan plan) {
        return switch (plan.proofStatus()) {
            case PROVEN_SAFE -> "已证明安全";
            case PROVEN_INFEASIBLE -> "已证明不可行";
            case UNPROVEN_TIMEOUT -> "时间预算内未证明";
            case UNPROVEN_SEARCH_BUDGET -> "搜索预算内未证明";
            case UNPROVEN_HEURISTIC_MISS -> "当前预算内未证明";
            case NOT_EVALUATED -> "未参与求解";
        };
    }

    /**
     * 追加当前模式的匿名停转说明。
     *
     * @param result 输出缓冲区
     * @param plan   刷新操作指令
     */
    private void appendPauseText(StringBuilder result, OcRefreshInstructionPlan plan) {
        if (!plan.pauseAllowed()) {
            result.append("\n- 当前模式不允许主动新增停转");
            return;
        }
        result.append("\n- 当前模式允许不超过")
                .append(OcTimelinePolicy.maxNewPause(plan.mode()).toHours())
                .append("小时的可恢复停转");
        if (plan.pauseSelected()) {
            result.append("；本次选择按当前业务价值顺序使用了可恢复停转");
        } else {
            result.append("；本次未选择停转");
        }
        if (plan.valueEvidenceLevel() == OcValueEvidence.Level.INSUFFICIENT
                || plan.valueEvidenceLevel() == OcValueEvidence.Level.PRIOR_ONLY) {
            result.append("，价值比较使用业务先验");
        }
    }

    /**
     * 追加配置或求解警告。
     *
     * @param result 输出缓冲区
     * @param plan   刷新操作指令
     */
    private void appendWarnings(StringBuilder result, OcRefreshInstructionPlan plan) {
        if (plan.warnings().isEmpty()) {
            return;
        }
        result.append("\n\n【配置/求解警告】");
        plan.warnings().stream().distinct()
                .forEach(warning -> result.append("\n- ").append(warning));
    }
}
