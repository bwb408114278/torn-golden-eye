package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.util.Map;
import java.util.List;

/**
 * 刷新安全边界计算上下文。
 *
 * @param request 联合刷新安全边界求解请求
 * @param plannedEmptyOcCounts 当前计划内无人OC数量，键为OC规划键
 * @param warnings 计划上下文构造警告
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
public record OcRefreshPlanningContext(OcRefreshSafetyRequest request,
                                       Map<String, Integer> plannedEmptyOcCounts,
                                       List<String> warnings) {
    public OcRefreshPlanningContext {
        plannedEmptyOcCounts = plannedEmptyOcCounts == null
                ? Map.of() : Map.copyOf(plannedEmptyOcCounts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
