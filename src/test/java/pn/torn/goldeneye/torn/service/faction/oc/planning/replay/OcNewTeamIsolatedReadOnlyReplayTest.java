package pn.torn.goldeneye.torn.service.faction.oc.planning.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import pn.torn.goldeneye.base.larksuite.LarkSuiteApi;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.socket.BotSocketClient;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.api.OcNewTeamPlanRenderer;
import pn.torn.goldeneye.torn.service.faction.oc.planning.api.OcRefreshInstructionPlanner;
import pn.torn.goldeneye.torn.service.faction.oc.planning.snapshot.OcPlanningSnapshotLoader;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NOV、PN当前真实快照的隔离只读回放。使用最小规划测试上下文，不启动
 * GoldenEyeApplication、NapCat、Torn API、Lark、Redis或任务调度器；
 * 输入加载和规划运行在REQUIRES_NEW + READ ONLY + REPEATABLE READ事务内。
 * 默认不创建上下文也不连接数据库，仅在{@code -Doc.replay.enabled=true}时执行。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OcPlannerReplayTestConfiguration.class)
@EnabledIfSystemProperty(named = "oc.replay.enabled", matches = "true")
@DisplayName("OC新队隔离真实只读回放")
class OcNewTeamIsolatedReadOnlyReplayTest {
    private static final long NOV_FACTION = 16335L;
    private static final long PN_FACTION = 20465L;

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private OcPlanningSnapshotLoader snapshotLoader;
    @Autowired
    private OcRefreshInstructionPlanner planner;
    @Autowired
    private OcNewTeamPlanRenderer renderer;
    @Autowired
    private OcPlanningReadOnlyGuard guard;
    @Autowired
    private TornFactionOcDAO ocDao;
    @Autowired
    private TornFactionOcSlotDAO slotDao;
    @Autowired
    private TornFactionOcUserDAO ocUserDao;

    @Test
    @DisplayName("NOV当前快照三模式双跑应确定且业务表零写")
    void shouldReplayNovSnapshotDeterministically() {
        replayFaction(NOV_FACTION);
    }

    @Test
    @DisplayName("PN当前快照三模式双跑应确定且业务表零写")
    void shouldReplayPnSnapshotDeterministically() {
        replayFaction(PN_FACTION);
    }

    @Test
    @DisplayName("最小上下文不应包含外部系统组件")
    void shouldNotContainExternalSystemBeans() {
        assertEquals(0, applicationContext.getBeanNamesForType(BotSocketClient.class).length);
        assertEquals(0, applicationContext.getBeanNamesForType(TornApi.class).length);
        assertEquals(0, applicationContext.getBeanNamesForType(LarkSuiteApi.class).length);
        assertEquals(0, applicationContext.getBeanNamesForType(RedisTemplate.class).length);
        assertEquals(0, applicationContext.getBeanNamesForType(TaskScheduler.class).length);
    }

    private void replayFaction(long factionId) {
        guard.verifyReadOnlySession();
        ReplayEvidence evidence = guard.inReadOnlyTransaction(status -> {
            long ocCountBefore = ocDao.count();
            long slotCountBefore = slotDao.count();
            long ocUserCountBefore = ocUserDao.count();
            LocalDateTime snapshotTime = LocalDateTime.now();
            OcPlanningSnapshot snapshot = snapshotLoader.load(factionId, snapshotTime);
            List<String> memberNicknames = snapshot.members().stream()
                    .map(OcMemberCandidate::nickname)
                    .filter(name -> name != null && !name.isBlank())
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
                assertTrue(first.normalRefreshCount() >= 0);
                assertTrue(first.highRefreshCount() >= 0);
                assertNotNull(first.proofStatus());
                assertNotSame(OcProofStatusEnum.NOT_EVALUATED, first.proofStatus(),
                        "已配置帮派必须参与求解");
                if (first.normalRefreshCount() > 0 || first.highRefreshCount() > 0) {
                    assertNotEquals(OcProofStatusEnum.PROVEN_INFEASIBLE,
                            first.proofStatus(), "正建议向量不得来自已证明不可行结果");
                    assertFalse(first.riskFlags().contains(OcRiskFlagEnum.DEADLOCK_RISK));
                    assertFalse(first.riskFlags()
                            .contains(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK));
                }
                String rendered = renderer.render(first);
                memberNicknames.forEach(nickname ->
                        assertFalse(rendered.contains(nickname),
                                "渲染输出必须匿名，不得包含成员昵称"));
            }
            return new ReplayEvidence(ocCountBefore, slotCountBefore, ocUserCountBefore,
                    ocDao.count(), slotDao.count(), ocUserDao.count());
        });
        assertEquals(evidence.ocCountBefore(), evidence.ocCountAfter());
        assertEquals(evidence.slotCountBefore(), evidence.slotCountAfter());
        assertEquals(evidence.ocUserCountBefore(), evidence.ocUserCountAfter());
    }

    private void assertValidReplanWindow(OcReplanWindow window, LocalDateTime snapshotTime) {
        assertNotNull(window.nextReplanAt());
        assertNotNull(window.latestReplanAt());
        assertFalse(window.nextReplanAt().isAfter(window.latestReplanAt()),
                "下次重评估不得晚于最晚重评估");
        assertFalse(window.latestReplanAt().isBefore(snapshotTime.minusMinutes(1)),
                "最晚重评估不得早于快照时间");
        assertFalse(window.nextReplanAt().isBefore(snapshotTime.minusMinutes(1)),
                "下次重评估不得早于快照时间");
    }

    /**
     * 回放前后规划涉及业务表的行数证据。
     */
    private record ReplayEvidence(long ocCountBefore, long slotCountBefore,
                                  long ocUserCountBefore, long ocCountAfter,
                                  long slotCountAfter, long ocUserCountAfter) {
    }
}
