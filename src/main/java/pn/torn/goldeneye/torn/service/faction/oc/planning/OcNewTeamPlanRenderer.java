package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshInstructionPlan;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;

/**
 * 将匿名安全边界结果渲染为OC指挥官可执行的刷新指令。
 *
 * @author Bai
 * @version 1.2.10
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
        appendPlannedEmptyOcs(result, plan);
        appendInstruction(result, plan);
        appendWarnings(result, plan);
        return result.toString();
    }

    private void appendPlannedEmptyOcs(StringBuilder result, OcRefreshInstructionPlan plan) {
        if (plan.plannedEmptyOcCounts().isEmpty()) {
            return;
        }
        result.append("\n\n【当前计划OC】");
        plan.plannedEmptyOcCounts().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> rank(entry.getKey()))
                        .thenComparing(entry -> name(entry.getKey())))
                .forEach(entry -> result.append("\n- ").append(rank(entry.getKey())).append("级 ")
                        .append(name(entry.getKey())).append(": ").append(entry.getValue()).append("个"));
    }

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
    }

    private void appendWarnings(StringBuilder result, OcRefreshInstructionPlan plan) {
        if (plan.warnings().isEmpty()) {
            return;
        }
        result.append("\n\n【配置/求解警告】");
        plan.warnings().stream().distinct()
                .forEach(warning -> result.append("\n- ").append(warning));
    }

    private int rank(String key) {
        return Integer.parseInt(key.substring(0, key.indexOf(':')));
    }

    private String name(String key) {
        return key.substring(key.indexOf(':') + 1);
    }
}
