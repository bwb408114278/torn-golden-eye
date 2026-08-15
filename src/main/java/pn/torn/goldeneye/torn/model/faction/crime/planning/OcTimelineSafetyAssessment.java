package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 一次时间线求解的整体安全评估。
 *
 * @param configurationStatus   配置状态
 * @param proofStatus           证明状态
 * @param riskFlags             业务风险标记集合
 * @param lowerBound            建议是否仅为已证明刷新向量下界
 * @param reasonCodes           匿名原因码集合
 * @param anchors               已证明流动性锚点链
 * @param nextCriticalReleaseAt 下一关键成员释放时间；无法证明时为null
 * @param proofWindowEnd        证明窗口结束时间（最晚重新评估时间）
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcTimelineSafetyAssessment(
        OcConfigurationStatusEnum configurationStatus,
        OcProofStatusEnum proofStatus,
        Set<OcRiskFlagEnum> riskFlags,
        boolean lowerBound,
        Set<OcPlanReasonCodeEnum> reasonCodes,
        List<OcLiquidityAnchor> anchors,
        LocalDateTime nextCriticalReleaseAt,
        LocalDateTime proofWindowEnd) {
    public OcTimelineSafetyAssessment {
        riskFlags = riskFlags == null ? Set.of() : Set.copyOf(riskFlags);
        reasonCodes = reasonCodes == null ? Set.of() : Set.copyOf(reasonCodes);
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
    }
}
