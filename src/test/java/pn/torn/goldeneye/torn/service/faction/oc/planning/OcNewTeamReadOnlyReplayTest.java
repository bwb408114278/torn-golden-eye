package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanningSnapshot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshInstructionPlan;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcReplanWindow;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NOV、PN当前真实快照只读回放。只调用生产纯规划引擎，不同步Torn数据、不写业务表，
 * 证明当前配置、现实占用、启用计划根和完整链在真实数据上可运行且同一快照结果确定。
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

    @Test
    @DisplayName("NOV当前快照三模式回放应确定且输出有效重评估窗口")
    void shouldReplayNovSnapshotDeterministically() {
        replayFaction(NOV_FACTION);
    }

    @Test
    @DisplayName("PN当前快照三模式回放应确定且输出有效重评估窗口")
    void shouldReplayPnSnapshotDeterministically() {
        replayFaction(PN_FACTION);
    }

    /**
     * 在同一只读快照上运行三模式并校验结构不变量。
     *
     * @param factionId 帮派ID
     */
    private void replayFaction(long factionId) {
        LocalDateTime snapshotTime = LocalDateTime.now();
        OcPlanningSnapshot snapshot = snapshotLoader.load(factionId, snapshotTime);
        for (OcPlanMode mode : OcPlanMode.values()) {
            OcRefreshInstructionPlan first = planner.plan(snapshot, mode);
            OcRefreshInstructionPlan second = planner.plan(snapshot, mode);
            assertNotNull(first.replanWindow());
            assertValidReplanWindow(first.replanWindow(), snapshotTime);
            assertEquals(first.normalRefreshCount(), second.normalRefreshCount());
            assertEquals(first.highRefreshCount(), second.highRefreshCount());
            assertEquals(first.configurationStatus(), second.configurationStatus());
            assertEquals(first.proofStatus(), second.proofStatus());
            assertNotNull(first.reason());
            assertTrue(first.normalRefreshCount() >= 0 && first.highRefreshCount() >= 0);
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
