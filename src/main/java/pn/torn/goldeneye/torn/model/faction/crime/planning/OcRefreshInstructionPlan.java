package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 面向OC指挥官的匿名刷新操作指令。
 *
 * @param factionId             帮派ID
 * @param snapshotTime          快照时间
 * @param mode                  刷新策略模式
 * @param plannedEmptyOcCounts  当前计划内无人OC数量，键为OC规划键
 * @param normalRefreshCount    普通池建议刷新次数
 * @param highRefreshCount      高阶池建议刷新次数
 * @param lowerBound            建议次数是否仅为已证明安全下界
 * @param reason                刷新建议原因
 * @param configurationStatus   配置状态
 * @param proofStatus           证明状态
 * @param riskFlags             业务风险标记集合
 * @param reasonCodes           匿名原因码集合
 * @param nextCriticalReleaseAt 下一关键成员释放时间；无法证明时为null
 * @param pauseAllowed          当前模式是否允许可恢复停转
 * @param pauseSelected         是否实际选择了可恢复停转
 * @param selectedPauseDuration 选择停转的匿名时长；未选择时为null
 * @param replanWindow          重新评估窗口
 * @param valueEvidenceLevel    价值证据层级
 * @param occupancySummary      当前全部现实OC和达标成员占用摘要
 * @param warnings              配置或求解警告
 * @author Bai
 * @version 1.3.0
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
                                       OcConfigurationStatusEnum configurationStatus,
                                       OcProofStatusEnum proofStatus,
                                       Set<OcRiskFlagEnum> riskFlags,
                                       Set<OcPlanReasonCodeEnum> reasonCodes,
                                       LocalDateTime nextCriticalReleaseAt,
                                       boolean pauseAllowed,
                                       boolean pauseSelected,
                                       Duration selectedPauseDuration,
                                       OcReplanWindow replanWindow,
                                       OcValueEvidence.Level valueEvidenceLevel,
                                       OcCurrentOccupancySummary occupancySummary,
                                       List<String> warnings) {
    public OcRefreshInstructionPlan {
        plannedEmptyOcCounts = plannedEmptyOcCounts == null
                ? Map.of() : Map.copyOf(plannedEmptyOcCounts);
        riskFlags = riskFlags == null ? Set.of() : Set.copyOf(riskFlags);
        reasonCodes = reasonCodes == null ? Set.of() : Set.copyOf(reasonCodes);
        valueEvidenceLevel = valueEvidenceLevel == null
                ? OcValueEvidence.Level.INSUFFICIENT : valueEvidenceLevel;
        occupancySummary = occupancySummary == null
                ? new OcCurrentOccupancySummary(0, 0, 0, 0, 0, 0, 0)
                : occupancySummary;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
