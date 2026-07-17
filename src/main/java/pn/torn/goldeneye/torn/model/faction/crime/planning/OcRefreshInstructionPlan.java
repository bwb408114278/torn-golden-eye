package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 面向OC指挥官的刷新操作指令。
 *
 * @param factionId 帮派ID
 * @param snapshotTime 快照时间
 * @param mode 刷新策略模式
 * @param plannedEmptyOcCounts 当前计划内无人OC数量，键为OC规划键
 * @param normalRefreshCount 普通池建议刷新次数
 * @param highRefreshCount 高阶池建议刷新次数
 * @param lowerBound 建议次数是否仅为已证明安全下界
 * @param reason 刷新建议原因
 * @param warnings 配置或求解警告
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
public record OcRefreshInstructionPlan(long factionId,
                                       LocalDateTime snapshotTime,
                                       OcPlanMode mode,
                                       Map<String, Integer> plannedEmptyOcCounts,
                                       int normalRefreshCount,
                                       int highRefreshCount,
                                       boolean lowerBound,
                                       String reason,
                                       List<String> warnings) {
    public OcRefreshInstructionPlan {
        plannedEmptyOcCounts = plannedEmptyOcCounts == null
                ? Map.of() : Map.copyOf(plannedEmptyOcCounts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
