package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.util.List;
import java.util.Map;

/**
 * 刷新时间线计算上下文。
 *
 * @param request              有限事件时间线求解请求
 * @param plannedEmptyOcCounts 当前计划内无人OC数量，键为OC规划键
 * @param configurationStatus  配置状态
 * @param warnings             计划上下文构造警告
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
public record OcRefreshPlanningContext(
        OcRefreshSafetyRequest request,
        Map<String, Integer> plannedEmptyOcCounts,
        OcConfigurationStatusEnum configurationStatus,
        List<String> warnings) {
    public OcRefreshPlanningContext {
        plannedEmptyOcCounts = plannedEmptyOcCounts == null
                ? Map.of() : Map.copyOf(plannedEmptyOcCounts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
