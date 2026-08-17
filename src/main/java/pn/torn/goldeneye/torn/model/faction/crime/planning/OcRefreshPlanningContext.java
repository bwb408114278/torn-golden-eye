package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 刷新时间线计算上下文。
 *
 * @param request              有限事件时间线求解请求
 * @param plannedEmptyOcCounts 当前计划内无人OC数量，键为OC规划键
 * @param configurationStatus  配置状态
 * @param warnings             计划上下文构造警告
 * @param riskFlags            重建得到的业务风险标记集合
 * @param reasonCodes          重建得到的匿名原因码集合
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
public record OcRefreshPlanningContext(
        OcRefreshSafetyRequest request,
        Map<String, Integer> plannedEmptyOcCounts,
        OcConfigurationStatusEnum configurationStatus,
        List<String> warnings,
        Set<OcRiskFlagEnum> riskFlags,
        Set<OcPlanReasonCodeEnum> reasonCodes) {
    public OcRefreshPlanningContext {
        plannedEmptyOcCounts = plannedEmptyOcCounts == null
                ? Map.of() : Map.copyOf(plannedEmptyOcCounts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        riskFlags = riskFlags == null ? Set.of() : Set.copyOf(riskFlags);
        reasonCodes = reasonCodes == null ? Set.of() : Set.copyOf(reasonCodes);
    }

    /**
     * 兼容旧调用：无重建风险事实时使用空集合。
     */
    public OcRefreshPlanningContext(OcRefreshSafetyRequest request,
                                    Map<String, Integer> plannedEmptyOcCounts,
                                    OcConfigurationStatusEnum configurationStatus,
                                    List<String> warnings) {
        this(request, plannedEmptyOcCounts, configurationStatus, warnings, Set.of(), Set.of());
    }
}
