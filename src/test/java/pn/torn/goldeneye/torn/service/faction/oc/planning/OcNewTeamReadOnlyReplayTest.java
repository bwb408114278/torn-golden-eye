package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NOV、PN当前真实快照只读回放。只调用生产纯规划引擎，不同步Torn数据、不写业务表，
 * 证明当前配置、现实占用、启用计划根和完整链在真实数据上可运行且同一快照结果确定。
 *
 * <p>该测试会启动完整Spring环境并连接真实数据库，不参与默认单元测试集合，
 * 仅在人工发布验收时通过系统属性{@code -Doc.replay.enabled=true}显式执行。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@SpringBootTest
@DisplayName("OC新队真实只读回放")
class OcNewTeamReadOnlyReplayTest {
    private static final long NOV_FACTION = 16335L;
    private static final long PN_FACTION = 20465L;

    @Autowired
    private OcPlanningSnapshotLoader snapshotLoader;
    @Autowired
    private OcRefreshInstructionPlanner planner;
    @Autowired
    private OcNewTeamPlanRenderer renderer;

    @Test
    @EnabledIfSystemProperty(named = "oc.replay.enabled", matches = "true")
    @DisplayName("NOV当前快照三模式回放应确定且输出有效重评估窗口")
    void shouldReplayNovSnapshotDeterministically() {
        replayFaction(NOV_FACTION);
    }

    @Test
    @EnabledIfSystemProperty(named = "oc.replay.enabled", matches = "true")
    @DisplayName("PN当前快照三模式回放应确定且输出有效重评估窗口")
    void shouldReplayPnSnapshotDeterministically() {
        replayFaction(PN_FACTION);
    }

    /**
     * 在同一只读快照上运行三模式并校验结构不变量、匿名性和确定性。
     *
     * @param factionId 帮派ID
     */
    private void replayFaction(long factionId) {
        LocalDateTime snapshotTime = LocalDateTime.now();
        OcPlanningSnapshot snapshot = snapshotLoader.load(factionId, snapshotTime);
        List<String> memberNicknames = snapshot.members().stream()
                .map(OcMemberCandidate::nickname).filter(name -> name != null && !name.isBlank())
                .toList();
        for (OcPlanMode mode : OcPlanMode.values()) {
            OcRefreshInstructionPlan first = planner.plan(snapshot, mode);
            OcRefreshInstructionPlan second = planner.plan(snapshot, mode);
            assertNotNull(first.replanWindow());
            assertValidReplanWindow(first.replanWindow(), snapshotTime);
            assertEquals(first.normalRefreshCount(), second.normalRefreshCount());
            assertEquals(first.highRefreshCount(), second.highRefreshCount());
            assertEquals(first.configurationStatus(), second.configurationStatus());
            assertEquals(first.proofStatus(), second.proofStatus());
            assertEquals(first.reasonCodes(), second.reasonCodes());
            assertNotNull(first.reason());
            assertTrue(first.normalRefreshCount() >= 0 && first.highRefreshCount() >= 0);
            assertNotNull(first.proofStatus());
            assertNotSame(OcProofStatusEnum.NOT_EVALUATED, first.proofStatus(), "已配置帮派必须参与求解");
            if (first.normalRefreshCount() > 0 || first.highRefreshCount() > 0) {
                assertNotEquals(OcProofStatusEnum.PROVEN_INFEASIBLE, first.proofStatus(),
                        "正建议向量不得来自已证明不可行结果");
                assertFalse(first.riskFlags().contains(OcRiskFlagEnum.DEADLOCK_RISK));
                assertFalse(first.riskFlags()
                        .contains(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK));
            }
            String rendered = renderer.render(first);
            memberNicknames.forEach(nickname ->
                    assertFalse(rendered.contains(nickname), "渲染输出必须匿名，不得包含成员昵称"));
        }
    }

    /**
     * 校验重评估窗口结构不变量。
     *
     * @param window       重新评估窗口
     * @param snapshotTime 快照时间
     */
    private void assertValidReplanWindow(OcReplanWindow window, LocalDateTime snapshotTime) {
        assertNotNull(window.nextReplanAt());
        assertNotNull(window.latestReplanAt());
        assertFalse(window.nextReplanAt().isAfter(window.latestReplanAt()), "下次重评估不得晚于最晚重评估");
        assertFalse(window.latestReplanAt().isBefore(snapshotTime.minusMinutes(1)), "最晚重评估不得早于快照时间");
        assertFalse(window.nextReplanAt().isBefore(snapshotTime.minusMinutes(1)), "下次重评估不得早于快照时间");
    }

}
