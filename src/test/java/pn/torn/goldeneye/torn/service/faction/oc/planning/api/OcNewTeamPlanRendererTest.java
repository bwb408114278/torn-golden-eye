package pn.torn.goldeneye.torn.service.faction.oc.planning.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC刷新指令渲染器测试，覆盖精简群消息结构、三种时间状态和零刷新P0安全契约。
 *
 * <p>P0契约：两池刷新次数均为0时，“建议下次刷新时间”绝不输出“现在”；
 * 有刷新次数时保持既有立即状态行为。</p>
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.07.17
 */
@DisplayName("OC刷新指令渲染")
class OcNewTeamPlanRendererTest {
    private final OcNewTeamPlanRenderer renderer = new OcNewTeamPlanRenderer();
    private static final LocalDateTime SNAPSHOT_TIME = LocalDateTime.of(2026, 7, 16, 15, 4);

    @Test
    @DisplayName("正常均衡模式：输出完整四区块和时间范围")
    void shouldRenderFullMessageForBalancedMode() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .mode(OcPlanMode.BALANCED)
                .normal(2)
                .high(1)
                .snapshotTime(SNAPSHOT_TIME)
                .nextReplanAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .latestReplanAt(LocalDateTime.of(2026, 7, 16, 17, 30))
                .nextCriticalReleaseAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .build();

        String text = renderer.render(plan);

        assertEquals("""
                【OC新队#均衡】 执行时间: 07-16 15:04
                
                【刷新指令】
                普通池: 刷新2次
                高阶池: 刷新1次
                完成后重新运行 OC新队#均衡
                
                【下次刷新时间】
                建议下次刷新时间: 07-16 16:00 - 07-16 17:30
                
                【当前OC状态】
                当前队伍: 3个（含1个无人OC）
                实际占用成员: 8人
                空闲达标成员: 5人
                下一批关键成员预计 07-16 16:00 释放
                """.strip(), text);
    }

    @Test
    @DisplayName("仅普通池非零：不输出高阶池行")
    void shouldRenderOnlyNormalPoolWhenHighZero() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .normal(2)
                .high(0)
                .nextReplanAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .latestReplanAt(LocalDateTime.of(2026, 7, 16, 17, 30))
                .nextCriticalReleaseAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("普通池: 刷新2次"));
        assertFalse(text.contains("高阶池"));
        assertTrue(text.contains("完成后重新运行 OC新队#均衡"));
    }

    @Test
    @DisplayName("仅高阶池非零：不输出普通池行")
    void shouldRenderOnlyHighPoolWhenNormalZero() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .normal(0)
                .high(1)
                .nextReplanAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .latestReplanAt(LocalDateTime.of(2026, 7, 16, 17, 30))
                .nextCriticalReleaseAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("高阶池: 刷新1次"));
        assertFalse(text.contains("普通池"));
        assertTrue(text.contains("完成后重新运行 OC新队#均衡"));
    }

    @Test
    @DisplayName("两池均为零：输出暂不刷新且不输出完成后提示")
    void shouldRenderNoRefreshWhenBothZero() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .normal(0)
                .high(0)
                .nextReplanAt(SNAPSHOT_TIME)
                .latestReplanAt(SNAPSHOT_TIME)
                .nextCriticalReleaseAt(null)
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("暂不刷新"));
        assertFalse(text.contains("完成后重新运行"));
        assertFalse(text.contains("普通池"));
        assertFalse(text.contains("高阶池"));
    }

    @Test
    @DisplayName("保守模式：输出模式正确且不输出停转说明")
    void shouldRenderConservativeModeWithoutPauseText() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .mode(OcPlanMode.CONSERVATIVE)
                .normal(1)
                .high(0)
                .nextReplanAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .latestReplanAt(LocalDateTime.of(2026, 7, 16, 17, 30))
                .nextCriticalReleaseAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("【OC新队#保守】 执行时间: 07-16 15:04"));
        assertFalse(text.contains("当前模式允许不超过"));
        assertFalse(text.contains("停转"));
    }

    @Test
    @DisplayName("收益模式：输出模式正确且不输出收益证据和价值比较")
    void shouldRenderProfitModeWithoutEvidenceText() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .mode(OcPlanMode.PROFIT)
                .normal(1)
                .high(1)
                .nextReplanAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .latestReplanAt(LocalDateTime.of(2026, 7, 16, 17, 30))
                .nextCriticalReleaseAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .planReasonCodes(Set.of(OcPlanReasonCodeEnum.ECONOMIC_EVIDENCE_INSUFFICIENT))
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("【OC新队#收益】 执行时间: 07-16 15:04"));
        assertFalse(text.contains("收益证据不足"));
        assertFalse(text.contains("价值比较"));
    }

    @Test
    @DisplayName("P0-1: 零刷新且不命中立即状态时输出完整未来范围而非现在")
    void shouldRenderRangeForNoRefreshWithFutureRange() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .normal(0)
                .high(0)
                .nextReplanAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .latestReplanAt(LocalDateTime.of(2026, 7, 16, 17, 30))
                .nextCriticalReleaseAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("暂不刷新"));
        assertTrue(text.contains("建议下次刷新时间: 07-16 16:00 - 07-16 17:30"));
        assertFalse(text.contains("建议下次刷新时间: 现在"));
    }

    @Test
    @DisplayName("P0-2至P0-5: 零刷新叠加任一立即原因码且有未来关键释放时输出释放后文案")
    void shouldRenderCriticalReleaseForNoRefreshWithEachImmediateReasonCode() {
        LocalDateTime release = SNAPSHOT_TIME.plusHours(2);
        List<OcPlanReasonCodeEnum> immediateCodes = List.of(
                OcPlanReasonCodeEnum.REPLAN_REQUIRED_NOW,
                OcPlanReasonCodeEnum.REPLAN_LEAD_TIME_ALREADY_ENTERED,
                OcPlanReasonCodeEnum.RANDOM_OUTCOME_CHANGED,
                OcPlanReasonCodeEnum.PROOF_WINDOW_EXPIRED_FOR_NEW_REFRESH);
        for (OcPlanReasonCodeEnum code : immediateCodes) {
            OcRefreshInstructionPlan plan = new PlanBuilder()
                    .normal(0)
                    .high(0)
                    .nextReplanAt(SNAPSHOT_TIME)
                    .latestReplanAt(SNAPSHOT_TIME)
                    .planReasonCodes(Set.of(code))
                    .nextCriticalReleaseAt(release)
                    .build();

            String text = renderer.render(plan);

            assertTrue(text.contains("暂不刷新"), () -> code + "应输出暂不刷新: " + text);
            assertTrue(text.contains("建议下次刷新时间: 关键成员释放后（预计 07-16 17:04）"),
                    () -> code + "应输出关键成员释放后: " + text);
            assertFalse(text.contains("建议下次刷新时间: 现在"), () -> code + "不应输出现在: " + text);
        }
    }

    @Test
    @DisplayName("P0-6: 零刷新且nextReplanAt等于快照时间时有未来释放仍输出释放后文案")
    void shouldRenderCriticalReleaseForNoRefreshWhenNextReplanAtEqualsSnapshot() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .normal(0)
                .high(0)
                .nextReplanAt(SNAPSHOT_TIME)
                .latestReplanAt(LocalDateTime.of(2026, 7, 16, 17, 30))
                .nextCriticalReleaseAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("建议下次刷新时间: 关键成员释放后（预计 07-16 16:00）"));
        assertFalse(text.contains("建议下次刷新时间: 现在"));
        assertFalse(text.contains("15:04 - 17:30"));
    }

    @Test
    @DisplayName("P0-7: 零刷新且无可证明未来事实时输出安全兜底文案而非现在")
    void shouldRenderSafeFallbackForNoRefreshWithoutProvableFuture() {
        List<OcRefreshInstructionPlan> noFuturePlans = List.of(
                new PlanBuilder().normal(0).high(0)
                        .nextReplanAt(SNAPSHOT_TIME).latestReplanAt(SNAPSHOT_TIME)
                        .nextCriticalReleaseAt(null).build(),
                new PlanBuilder().normal(0).high(0)
                        .nextReplanAt(SNAPSHOT_TIME).latestReplanAt(SNAPSHOT_TIME)
                        .windowReasonCodes(Set.of(OcPlanReasonCodeEnum.REPLAN_REQUIRED_NOW))
                        .nextCriticalReleaseAt(null).build(),
                new PlanBuilder().normal(0).high(0)
                        .nextReplanAt(SNAPSHOT_TIME).latestReplanAt(SNAPSHOT_TIME)
                        .windowReasonCodes(Set.of(OcPlanReasonCodeEnum.RANDOM_OUTCOME_CHANGED))
                        .nextCriticalReleaseAt(null).build(),
                new PlanBuilder().normal(0).high(0)
                        .nextReplanAt(null).latestReplanAt(null)
                        .nextCriticalReleaseAt(null).build());
        for (OcRefreshInstructionPlan noFuturePlan : noFuturePlans) {
            String text = renderer.render(noFuturePlan);

            assertTrue(text.contains("建议下次刷新时间: 下一次关键状态变化后重新评估"),
                    () -> describePlan(noFuturePlan) + "应输出安全兜底文案: " + text);
            assertFalse(text.contains("建议下次刷新时间: 现在"),
                    () -> describePlan(noFuturePlan) + "不应输出现在: " + text);
        }
    }

    @Test
    @DisplayName("P0-Z3: 零刷新叠加立即原因码且仅有未来重评估时间点时输出指定时间后重新评估")
    void shouldRenderReplanAfterTimeForNoRefreshWithFutureNextReplanOnly() {
        LocalDateTime singlePoint = SNAPSHOT_TIME.plusMinutes(30);
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .normal(0)
                .high(0)
                .nextReplanAt(singlePoint)
                .latestReplanAt(singlePoint)
                .windowReasonCodes(Set.of(OcPlanReasonCodeEnum.REPLAN_REQUIRED_NOW))
                .nextCriticalReleaseAt(null)
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("建议下次刷新时间: 07-16 15:34 后重新评估"));
        assertFalse(text.contains("建议下次刷新时间: 现在"));
        assertFalse(text.contains("15:34 - 15:34"));
    }

    @Test
    @DisplayName("R1: 有刷新次数且明确立即重评估时保持输出现在")
    void shouldRenderNowForRefreshCommandWhenReplanRequiredNow() {
        LocalDateTime singlePoint = SNAPSHOT_TIME.plusMinutes(30);
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .normal(1)
                .high(0)
                .nextReplanAt(singlePoint)
                .latestReplanAt(singlePoint)
                .windowReasonCodes(Set.of(OcPlanReasonCodeEnum.REPLAN_REQUIRED_NOW))
                .nextCriticalReleaseAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("建议下次刷新时间: 现在"));
        assertFalse(text.contains("后重新评估"));
    }

    @Test
    @DisplayName("PN生产回归：零刷新叠加立即原因码与未来关键释放时输出释放后文案")
    void shouldRenderCriticalReleaseForPnProductionEquivalentFixture() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .mode(OcPlanMode.PROFIT)
                .snapshotTime(LocalDateTime.of(2026, 8, 23, 19, 35))
                .normal(0)
                .high(0)
                .nextReplanAt(LocalDateTime.of(2026, 8, 23, 19, 35))
                .latestReplanAt(LocalDateTime.of(2026, 8, 23, 19, 35))
                .windowReasonCodes(Set.of(OcPlanReasonCodeEnum.REPLAN_REQUIRED_NOW))
                .nextCriticalReleaseAt(LocalDateTime.of(2026, 8, 24, 13, 10))
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("【OC新队#收益】 执行时间: 08-23 19:35"));
        assertTrue(text.contains("暂不刷新"));
        assertTrue(text.contains("建议下次刷新时间: 关键成员释放后（预计 08-24 13:10）"));
        assertFalse(text.contains("建议下次刷新时间: 现在"));
        assertFalse(text.contains("完成后重新运行"));
    }

    @Test
    @DisplayName("下次建议时间已到当前时应立即执行")
    void shouldRenderNowWhenNextReplanAtSnapshotTime() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .normal(1)
                .high(0)
                .nextReplanAt(SNAPSHOT_TIME)
                .latestReplanAt(LocalDateTime.of(2026, 7, 16, 17, 30))
                .nextCriticalReleaseAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("建议下次刷新时间: 现在"));
        assertFalse(text.contains("15:04 - 17:30"));
    }

    @Test
    @DisplayName("无法形成完整范围但有未来关键成员释放时输出等待事件")
    void shouldRenderWaitingCriticalReleaseWhenNoFullRange() {
        LocalDateTime nextEvent = SNAPSHOT_TIME.plusHours(2);
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .normal(1)
                .high(0)
                .nextReplanAt(nextEvent)
                .latestReplanAt(SNAPSHOT_TIME)
                .nextCriticalReleaseAt(nextEvent)
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("建议下次刷新时间: 关键成员释放后（预计 07-16 17:04）"));
        assertFalse(text.contains(" - "));
    }

    @Test
    @DisplayName("无下一关键释放时间时输出统一无事件文案")
    void shouldRenderNoNextCriticalReleaseText() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .normal(1)
                .high(0)
                .nextReplanAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .latestReplanAt(LocalDateTime.of(2026, 7, 16, 17, 30))
                .nextCriticalReleaseAt(null)
                .build();

        String text = renderer.render(plan);

        assertTrue(text.contains("当前无可证明的下一批关键成员释放时间"));
        assertFalse(text.contains("null"));
    }

    @Test
    @DisplayName("旧长消息字段不得泄露")
    void shouldNotLeakOldInternalFields() {
        OcRefreshInstructionPlan plan = new PlanBuilder()
                .mode(OcPlanMode.PROFIT)
                .normal(1)
                .high(1)
                .nextReplanAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .latestReplanAt(LocalDateTime.of(2026, 7, 16, 17, 30))
                .nextCriticalReleaseAt(LocalDateTime.of(2026, 7, 16, 16, 0))
                .planReasonCodes(Set.of(OcPlanReasonCodeEnum.SAFE_LOWER_BOUND_ONLY))
                .build();

        String text = renderer.render(plan);

        assertFalse(text.contains("【时间线评估】"));
        assertFalse(text.contains("【配置/求解警告】"));
        assertFalse(text.contains("证明状态"));
        assertFalse(text.contains("原因:"));
        assertFalse(text.contains("当前模式允许不超过"));
        assertFalse(text.contains("收益证据不足"));
        assertFalse(text.contains("快照时间"));
        assertFalse(text.contains("建议重新评估窗口"));
        assertFalse(text.contains("已进入操作提前区间"));
        assertFalse(text.contains("Worker#"));
        assertFalse(text.contains("member"));
    }

    /**
     * 描述零刷新夹具的窗口事实，用于断言失败时定位具体变体。
     *
     * @param plan 刷新操作指令
     * @return 包含窗口原因码与重评估时间的描述文本
     */
    private String describePlan(OcRefreshInstructionPlan plan) {
        OcReplanWindow window = plan.replanWindow();
        return "夹具[windowCodes=" + window.reasonCodes() + ", nextReplanAt=" + window.nextReplanAt() + "]";
    }

    /**
     * OC刷新指令测试专用构造器，避免重复编写超长record构造参数。
     */
    private static final class PlanBuilder {
        private OcPlanMode mode = OcPlanMode.BALANCED;
        private int normal;
        private int high;
        private LocalDateTime snapshotTime = SNAPSHOT_TIME;
        private LocalDateTime nextReplanAt = LocalDateTime.of(2026, 7, 16, 16, 0);
        private LocalDateTime latestReplanAt = LocalDateTime.of(2026, 7, 16, 17, 30);
        private Set<OcPlanReasonCodeEnum> windowReasonCodes = Set.of();
        private LocalDateTime nextCriticalReleaseAt = LocalDateTime.of(2026, 7, 16, 16, 0);
        private Set<OcPlanReasonCodeEnum> planReasonCodes = Set.of();

        PlanBuilder mode(OcPlanMode mode) {
            this.mode = mode;
            return this;
        }

        PlanBuilder normal(int normal) {
            this.normal = normal;
            return this;
        }

        PlanBuilder high(int high) {
            this.high = high;
            return this;
        }

        PlanBuilder snapshotTime(LocalDateTime snapshotTime) {
            this.snapshotTime = snapshotTime;
            return this;
        }

        PlanBuilder nextReplanAt(LocalDateTime nextReplanAt) {
            this.nextReplanAt = nextReplanAt;
            return this;
        }

        PlanBuilder latestReplanAt(LocalDateTime latestReplanAt) {
            this.latestReplanAt = latestReplanAt;
            return this;
        }

        PlanBuilder windowReasonCodes(Set<OcPlanReasonCodeEnum> windowReasonCodes) {
            this.windowReasonCodes = windowReasonCodes;
            return this;
        }

        PlanBuilder nextCriticalReleaseAt(LocalDateTime nextCriticalReleaseAt) {
            this.nextCriticalReleaseAt = nextCriticalReleaseAt;
            return this;
        }

        PlanBuilder planReasonCodes(Set<OcPlanReasonCodeEnum> planReasonCodes) {
            this.planReasonCodes = planReasonCodes;
            return this;
        }

        OcRefreshInstructionPlan build() {
            return new OcRefreshInstructionPlan(20465L, snapshotTime, mode,
                    Map.of("8:Clinical Precision", 1, "8:Stacking the Deck", 0),
                    normal, high, true, "内部原因不应出现在群消息",
                    OcConfigurationStatusEnum.VALID, OcProofStatusEnum.PROVEN_SAFE,
                    Set.of(OcRiskFlagEnum.ECONOMIC_EVIDENCE_INSUFFICIENT),
                    planReasonCodes, nextCriticalReleaseAt, true, true, Duration.ofHours(2),
                    new OcReplanWindow(nextReplanAt, latestReplanAt, windowReasonCodes),
                    OcValueEvidence.Level.PRIOR_ONLY,
                    new OcCurrentOccupancySummary(3, 2, 1, 8, 12, 7, 5),
                    List.of("内部求解警告不应出现在群消息"));
        }
    }
}
