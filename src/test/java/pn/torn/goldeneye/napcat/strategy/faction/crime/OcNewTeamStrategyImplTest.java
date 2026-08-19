package pn.torn.goldeneye.napcat.strategy.faction.crime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcRefreshManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshInstructionPlan;
import pn.torn.goldeneye.torn.service.faction.oc.planning.api.OcNewTeamPlanRenderer;
import pn.torn.goldeneye.torn.service.faction.oc.planning.api.OcNewTeamPlanningFacade;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OC新队指令策略编排测试。只验证生产入口的调用接线与随机结果布尔语义，
 * 不重复规划器的窗口规则矩阵。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC新队指令策略编排")
class OcNewTeamStrategyImplTest {
    private static final long FACTION_ID = 16335L;
    private static final long GROUP_ID = 1L;

    @Mock
    private TornFactionOcRefreshManager ocRefreshManager;
    @Mock
    private OcNewTeamPlanningFacade planningFacade;
    @Mock
    private OcNewTeamPlanRenderer renderer;
    @Mock
    private ProjectProperty projectProperty;
    @Mock
    private TornUserManager userManager;

    private OcNewTeamStrategyImpl strategy;

    @BeforeEach
    void setUp() {
        strategy = new OcNewTeamStrategyImpl(ocRefreshManager, planningFacade, renderer,
                projectProperty);
        ReflectionTestUtils.setField(strategy, "userManager", userManager);
    }

    @Test
    @DisplayName("生产环境：先同步本地OC数据，再以false调用规划门面")
    void handle_prodEnv_syncsThenPlansWithoutRandomOutcomeChange() {
        when(projectProperty.getEnv()).thenReturn("prod");
        stubFactionUser();
        when(planningFacade.plan(FACTION_ID, OcPlanMode.BALANCED, false))
                .thenReturn(mock(OcRefreshInstructionPlan.class));
        when(renderer.render(any())).thenReturn("规划结果");

        strategy.handle(GROUP_ID, sender(), "均衡");

        InOrder inOrder = inOrder(ocRefreshManager, planningFacade);
        inOrder.verify(ocRefreshManager).refreshOc(1, FACTION_ID);
        inOrder.verify(planningFacade).plan(FACTION_ID, OcPlanMode.BALANCED, false);
        verify(planningFacade, never()).plan(anyLong(), any(), eq(true));
    }

    @Test
    @DisplayName("非生产环境：不同步本地数据，直接以false调用规划门面")
    void handle_nonProdEnv_plansWithoutSyncOrRandomOutcomeChange() {
        when(projectProperty.getEnv()).thenReturn("dev");
        stubFactionUser();
        when(planningFacade.plan(FACTION_ID, OcPlanMode.PROFIT, false))
                .thenReturn(mock(OcRefreshInstructionPlan.class));
        when(renderer.render(any())).thenReturn("规划结果");

        strategy.handle(GROUP_ID, sender(), "收益");

        verifyNoInteractions(ocRefreshManager);
        verify(planningFacade).plan(FACTION_ID, OcPlanMode.PROFIT, false);
        verify(planningFacade, never()).plan(anyLong(), any(), eq(true));
    }

    @Test
    @DisplayName("无效二级指令：不触发同步、规划门面和渲染")
    void handle_invalidSubCommand_skipsSyncPlanAndRender() {
        strategy.handle(GROUP_ID, sender(), "无效模式");

        verifyNoInteractions(ocRefreshManager, planningFacade, renderer);
    }

    @Test
    @DisplayName("无效二级指令：回复固定的二级指令说明")
    void handle_invalidSubCommand_repliesSubCommandUsage() {
        List<? extends TextQqMsg> messages = castTextMessages(
                strategy.handle(GROUP_ID, sender(), "无效模式"));

        assertTrue(messages.getFirst().getData().text().contains("二级指令仅支持"),
                "无效二级指令必须回复固定的二级指令说明");
    }

    /**
     * 将策略返回消息按文本消息收窄类型。
     *
     * @param messages 策略返回消息
     * @return 文本消息列表
     */
    @SuppressWarnings("unchecked")
    private List<? extends TextQqMsg> castTextMessages(List<? extends QqMsgParam<?>> messages) {
        return (List<? extends TextQqMsg>) messages;
    }

    /**
     * 构造带QQ号的发送人。
     *
     * @return 发送人
     */
    private QqRecMsgSender sender() {
        QqRecMsgSender sender = new QqRecMsgSender();
        sender.setUserId(10001L);
        return sender;
    }

    /**
     * 打桩发送人对应的帮派用户。
     */
    private void stubFactionUser() {
        TornUserDO user = mock(TornUserDO.class);
        when(user.getFactionId()).thenReturn(FACTION_ID);
        when(userManager.getUserByQq(10001L)).thenReturn(user);
    }
}
