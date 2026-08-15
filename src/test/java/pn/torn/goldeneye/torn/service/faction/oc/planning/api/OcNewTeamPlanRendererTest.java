package pn.torn.goldeneye.torn.service.faction.oc.planning.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OC刷新指令渲染器测试。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@DisplayName("OC刷新指令渲染")
class OcNewTeamPlanRendererTest {
    private final OcNewTeamPlanRenderer renderer = new OcNewTeamPlanRenderer();

    @Test
    @DisplayName("应渲染现实占用摘要和非零刷新次数")
    void shouldRenderCurrentOccupancyAndNonZeroRefreshCounts() {
        OcRefreshInstructionPlan plan = plan(OcPlanMode.BALANCED, 2, 1, Set.of());

        String text = renderer.render(plan);

        assertTrue(text.contains("普通池: 刷新2次"));
        assertTrue(text.contains("高阶池: 刷新1次"));
        assertTrue(text.contains("【当前OC占用】"));
        assertTrue(text.contains("当前队伍: 10个（已有人8个 / 无人2个）"));
        assertTrue(text.contains("实际占用成员: 30人"));
        assertTrue(text.contains("达标成员: 40人"));
        assertTrue(text.contains("已占用达标成员: 25人"));
        assertTrue(text.contains("空闲达标成员: 15人"));
    }

    @Test
    @DisplayName("应匿名渲染时间线评估、停转说明和重新评估窗口")
    void shouldRenderAnonymousTimelineAssessmentAndReplanWindow() {
        LocalDateTime snapshotTime = LocalDateTime.of(2026, 7, 16, 15, 4);
        OcReplanWindow replanWindow = new OcReplanWindow(
                LocalDateTime.of(2026, 7, 16, 16, 0),
                LocalDateTime.of(2026, 7, 16, 17, 30), Set.of());
        OcRefreshInstructionPlan plan = new OcRefreshInstructionPlan(20465L, snapshotTime,
                OcPlanMode.PROFIT, Map.of(), 2, 1, false, "已证明可承接",
                OcConfigurationStatusEnum.VALID, OcProofStatusEnum.PROVEN_SAFE,
                Set.of(OcRiskFlagEnum.RECOVERABLE_PAUSE_PRESENT),
                Set.of(OcPlanReasonCodeEnum.SAFE_LOWER_BOUND_ONLY),
                LocalDateTime.of(2026, 7, 16, 16, 0), true, true, Duration.ofHours(2),
                replanWindow, OcValueEvidence.Level.OBSERVED_REWARD,
                new OcCurrentOccupancySummary(10, 8, 2, 30, 40, 25, 15), List.of());

        String text = renderer.render(plan);

        assertTrue(text.contains("【时间线评估】"));
        assertTrue(text.contains("配置状态: 有效"));
        assertTrue(text.contains("证明状态: 已证明安全"));
        assertTrue(text.contains("当前不存在被迫拆队风险"));
        assertTrue(text.contains("下一批关键成员预计 07-16 16:00 释放"));
        assertTrue(text.contains("不超过12小时的可恢复停转"));
        assertTrue(text.contains("本次选择按当前业务价值顺序使用了可恢复停转"));
        assertTrue(text.contains("建议重新评估窗口: 07-16 16:00 ～ 07-16 17:30前"));
        assertTrue(text.contains("应立即重新运行指令"));
    }

    @Test
    @DisplayName("卡死风险时应匿名渲染风险而不泄露成员或岗位")
    void shouldRenderDeadlockRiskAnonymously() {
        OcRefreshInstructionPlan plan = new OcRefreshInstructionPlan(20465L,
                LocalDateTime.of(2026, 7, 16, 15, 4), OcPlanMode.CONSERVATIVE,
                Map.of(), 0, 0, false, "当前存在全帮卡死或被迫拆队风险",
                OcConfigurationStatusEnum.VALID, OcProofStatusEnum.PROVEN_INFEASIBLE,
                Set.of(OcRiskFlagEnum.DEADLOCK_RISK), Set.of(),
                null, false, false, null,
                new OcReplanWindow(LocalDateTime.of(2026, 7, 16, 15, 4),
                        LocalDateTime.of(2026, 7, 16, 15, 4), Set.of()),
                OcValueEvidence.Level.INSUFFICIENT,
                new OcCurrentOccupancySummary(3, 2, 1, 8, 12, 7, 5), List.of());

        String text = renderer.render(plan);

        assertTrue(text.contains("暂不刷新"));
        assertTrue(text.contains("当前存在全帮卡死或被迫拆队风险"));
        assertTrue(text.contains("当前模式不允许主动新增停转"));
        assertFalse(text.contains("member"));
        assertFalse(text.contains("Worker#"));
        assertFalse(text.contains("→"));
    }

    @Test
    @DisplayName("收益证据不足时不得输出收益最优的肯定语")
    void shouldNotClaimProfitOptimalWhenEconomicEvidenceInsufficient() {
        OcRefreshInstructionPlan plan = new OcRefreshInstructionPlan(20465L,
                LocalDateTime.of(2026, 7, 16, 15, 4), OcPlanMode.PROFIT,
                Map.of(), 0, 0, false, "已证明可承接",
                OcConfigurationStatusEnum.VALID, OcProofStatusEnum.PROVEN_SAFE,
                Set.of(OcRiskFlagEnum.ECONOMIC_EVIDENCE_INSUFFICIENT),
                Set.of(OcPlanReasonCodeEnum.ECONOMIC_EVIDENCE_INSUFFICIENT),
                null, true, false, null,
                new OcReplanWindow(LocalDateTime.of(2026, 7, 16, 16, 0),
                        LocalDateTime.of(2026, 7, 16, 17, 30), Set.of()),
                OcValueEvidence.Level.PRIOR_ONLY,
                new OcCurrentOccupancySummary(3, 2, 1, 8, 12, 7, 5), List.of());

        String text = renderer.render(plan);

        assertTrue(text.contains("收益证据不足，未据此提高刷新或停转建议"));
        assertFalse(text.contains("收益建议"));
        assertFalse(text.contains("收益更优"));
        assertFalse(text.contains("收益最优"));
    }

    private OcRefreshInstructionPlan plan(OcPlanMode mode, int normal, int high, Set<OcRiskFlagEnum> riskFlags) {
        return new OcRefreshInstructionPlan(20465L,
                LocalDateTime.of(2026, 7, 16, 15, 4), mode,
                Map.of("8:Clinical Precision", 1, "8:Stacking the Deck", 0),
                normal, high, false, "安全边界验证通过",
                OcConfigurationStatusEnum.VALID, OcProofStatusEnum.PROVEN_SAFE, riskFlags, Set.of(),
                LocalDateTime.of(2026, 7, 17, 8, 0), false, false, null,
                new OcReplanWindow(LocalDateTime.of(2026, 7, 17, 8, 0),
                        LocalDateTime.of(2026, 7, 18, 8, 0), Set.of()),
                OcValueEvidence.Level.OBSERVED_REWARD,
                new OcCurrentOccupancySummary(10, 8, 2, 30, 40, 25, 15), List.of());
    }
}
