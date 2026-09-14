package pn.torn.goldeneye.napcat.strategy.faction.crime.reassign;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import pn.torn.goldeneye.configuration.property.ProjectProperty;
import pn.torn.goldeneye.constants.torn.enums.TornFactionRoleTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.TornOcIncomeModeEnum;
import pn.torn.goldeneye.napcat.receive.msg.QqRecMsgSender;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.user.TornUserManager;
import pn.torn.goldeneye.torn.service.faction.oc.income.TornOcBatchIncomeService;
import pn.torn.goldeneye.torn.service.faction.oc.reassign.OcReassignConfigService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 大锅饭三个指令策略测试。
 *
 * <p>覆盖参数解析（默认当月1日、超管帮派ID前缀、非超管带前缀拒绝）、roleType/isNeedSa声明、
 * 成功路径转发编排服务与添加成功后的异步补算提交。回执文案按技术设计7.5逐字校验。</p>
 *
 * @author Bai
 * @version 1.6.2
 * @since 2026.09.14
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("大锅饭指令策略测试")
class OcReassignStrategyTest {
    private static final long ADMIN_QQ = 10001L;
    private static final long COMMANDER_QQ = 10002L;
    private static final long FACTION_BSU = 11796L;
    private static final long FACTION_PN = 20465L;

    @Mock
    private OcReassignConfigService reassignConfigService;
    @Mock
    private TornSettingFactionManager settingFactionManager;
    @Mock
    private ProjectProperty projectProperty;
    @Mock
    private ThreadPoolTaskExecutor virtualThreadExecutor;
    @Mock
    private TornOcBatchIncomeService ocBatchIncomeService;
    @Mock
    private TornUserManager userManager;

    private OcReassignOpenStrategyImpl openStrategy;
    private OcReassignAddStrategyImpl addStrategy;
    private OcReassignListStrategyImpl listStrategy;

    @BeforeEach
    void setUp() {
        openStrategy = new OcReassignOpenStrategyImpl(reassignConfigService, settingFactionManager);
        addStrategy = new OcReassignAddStrategyImpl(reassignConfigService, settingFactionManager,
                projectProperty, virtualThreadExecutor, ocBatchIncomeService);
        listStrategy = new OcReassignListStrategyImpl(reassignConfigService, settingFactionManager,
                projectProperty);
        ReflectionTestUtils.setField(addStrategy, "userManager", userManager);
        ReflectionTestUtils.setField(listStrategy, "userManager", userManager);
        lenient().when(projectProperty.getAdminId()).thenReturn(List.of(ADMIN_QQ));
        lenient().doReturn(factionShortNameMap()).when(settingFactionManager).getIdMap();
    }

    @Test
    @DisplayName("指令权限声明：开启仅超管，添加OC指挥官，名单无门槛")
    void roleDeclarations_matchDesign() {
        assertTrue(openStrategy.isNeedSa());
        assertFalse(addStrategy.isNeedSa());
        assertFalse(listStrategy.isNeedSa());
        assertNull(openStrategy.getRoleType());
        assertEquals(TornFactionRoleTypeEnum.OC_COMMANDER, addStrategy.getRoleType());
        assertNull(listStrategy.getRoleType());
    }

    @Test
    @DisplayName("开启成功回执逐字等价并转发服务")
    void open_success_rendersExactReceipt() {
        QqRecMsgSender sender = sender(ADMIN_QQ);
        when(reassignConfigService.openFaction(FACTION_PN, TornOcIncomeModeEnum.COEFFICIENT, ADMIN_QQ))
                .thenReturn(new OcReassignConfigService.OpenResult(true, null));

        List<? extends QqMsgParam<?>> msgs = openStrategy.handle(1L, sender, FACTION_PN + "#系数");

        assertEquals("大锅饭已开启: PHN(20465) 模式=系数", textOf(msgs));
        verify(reassignConfigService).openFaction(FACTION_PN, TornOcIncomeModeEnum.COEFFICIENT, ADMIN_QQ);
    }

    @Test
    @DisplayName("开启参数错误与模式非法分别回执格式提示")
    void open_invalidParams_returnsFormatMsg() {
        assertEquals("参数有误，正确格式：g#OC大锅饭开启#帮派ID#系数|平分",
                textOf(openStrategy.handle(1L, sender(ADMIN_QQ), "20465")));
        assertEquals("参数有误，正确格式：g#OC大锅饭开启#帮派ID#系数|平分",
                textOf(openStrategy.handle(1L, sender(ADMIN_QQ), "abc#系数")));
        assertEquals("参数有误，正确格式：g#OC大锅饭开启#帮派ID#系数|平分",
                textOf(openStrategy.handle(1L, sender(ADMIN_QQ), "20465#平均")));
    }

    @Test
    @DisplayName("开启失败原因透传")
    void open_failure_passesThroughReason() {
        when(reassignConfigService.openFaction(FACTION_PN, TornOcIncomeModeEnum.EQUAL, ADMIN_QQ))
                .thenReturn(new OcReassignConfigService.OpenResult(false, "失败: 该帮派已开启大锅饭"));

        assertEquals("失败: 该帮派已开启大锅饭",
                textOf(openStrategy.handle(1L, sender(ADMIN_QQ), FACTION_PN + "#平分")));
    }

    @Test
    @DisplayName("无前缀添加：目标帮派取发送者绑定，未传日期由服务端默认")
    void add_withoutPrefix_usesSenderFactionAndNullDate() {
        QqRecMsgSender sender = sender(COMMANDER_QQ);
        stubSenderFaction(COMMANDER_QQ, FACTION_BSU);
        when(reassignConfigService.addOc(FACTION_BSU, "Ace in the Hole", null, COMMANDER_QQ))
                .thenReturn(new OcReassignConfigService.AddOcResult(true, null,
                        LocalDateTime.of(2026, 9, 1, 0, 0, 0), true, false));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(virtualThreadExecutor).execute(any(Runnable.class));

        List<? extends QqMsgParam<?>> msgs =
                addStrategy.handle(1L, sender, "Ace in the Hole");

        assertEquals("已加入大锅饭: BSU - Ace in the Hole\n"
                + "生效时间: 2026-09-01 00:00:00\n"
                + "规划范围: 已同步\n"
                + "补算: 已提交异步执行, 稍后可用[OC大锅饭名单]确认", textOf(msgs));
        verify(ocBatchIncomeService).requestBatchIncome(eq(FACTION_BSU), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("超管带帮派ID前缀：目标帮派取前缀，日期参数解析")
    void add_saPrefix_parsesFactionAndDate() {
        QqRecMsgSender sender = sender(ADMIN_QQ);
        when(reassignConfigService.addOc(FACTION_BSU, "Cleared for Takeoff",
                LocalDate.of(2026, 9, 1), ADMIN_QQ))
                .thenReturn(new OcReassignConfigService.AddOcResult(true, null,
                        LocalDateTime.of(2026, 9, 1, 0, 0, 0), false, true));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(virtualThreadExecutor).execute(any(Runnable.class));

        List<? extends QqMsgParam<?>> msgs =
                addStrategy.handle(1L, sender, FACTION_BSU + "#Cleared for Takeoff#2026-09-01");

        assertEquals("已加入大锅饭: BSU - Cleared for Takeoff\n"
                + "生效时间: 2026-09-01 00:00:00\n"
                + "规划范围: 规划行已存在\n"
                + "警告: 该OC缺少新队规划档案(torn_setting_oc_plan_profile), 自动规划不会规划该OC\n"
                + "补算: 已提交异步执行, 稍后可用[OC大锅饭名单]确认", textOf(msgs));
    }

    @Test
    @DisplayName("非超管带帮派ID前缀直接拒绝且不调用服务")
    void add_nonSaWithPrefix_rejected() {
        QqRecMsgSender sender = sender(COMMANDER_QQ);

        assertEquals("失败: 仅超管可指定帮派ID",
                textOf(addStrategy.handle(1L, sender, FACTION_BSU + "#Ace in the Hole")));
        verify(reassignConfigService, never()).addOc(anyLong(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("添加校验失败原因透传且不提交补算")
    void add_validationFailure_passesThroughReason() {
        QqRecMsgSender sender = sender(COMMANDER_QQ);
        stubSenderFaction(COMMANDER_QQ, FACTION_BSU);
        when(reassignConfigService.addOc(FACTION_BSU, "Crane Reaction", null, COMMANDER_QQ))
                .thenReturn(new OcReassignConfigService.AddOcResult(false,
                        "失败: 链式OC缺少前序节点: 添加[Crane Reaction]需先加入[Manifest Cruelty]",
                        null, false, false));

        assertEquals("失败: 链式OC缺少前序节点: 添加[Crane Reaction]需先加入[Manifest Cruelty]",
                textOf(addStrategy.handle(1L, sender, "Crane Reaction")));
        verify(virtualThreadExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("名单默认查发送者帮派并渲染模式与逐行生效时间")
    void list_withoutPrefix_rendersSenderFaction() {
        QqRecMsgSender sender = sender(COMMANDER_QQ);
        stubSenderFaction(COMMANDER_QQ, FACTION_BSU);
        when(reassignConfigService.listFaction(FACTION_BSU)).thenReturn(new OcReassignConfigService.ListResult(
                true, null, FACTION_BSU, TornOcIncomeModeEnum.EQUAL, List.of(
                new OcReassignConfigService.ScopeRow("Break the Bank", 8, null, true),
                new OcReassignConfigService.ScopeRow("Ace in the Hole", 9,
                        LocalDateTime.of(2026, 9, 1, 0, 0, 0), false))));

        assertEquals("大锅饭名单: BSU(11796) 模式=平分\n"
                + "Break the Bank [8] 始终\n"
                + "Ace in the Hole [9] 2026-09-01 00:00:00 缺规划行",
                textOf(listStrategy.handle(1L, sender, "")));
    }

    @Test
    @DisplayName("名单非超管带帮派ID前缀拒绝，未开启帮派回执提示")
    void list_nonSaPrefixOrUnopened_rejected() {
        assertEquals("失败: 仅超管可指定帮派ID",
                textOf(listStrategy.handle(1L, sender(COMMANDER_QQ), String.valueOf(FACTION_PN))));

        when(reassignConfigService.listFaction(FACTION_PN))
                .thenReturn(new OcReassignConfigService.ListResult(false, null, FACTION_PN, null, List.of()));
        QqRecMsgSender admin = sender(ADMIN_QQ);
        assertEquals("该帮派未开启大锅饭",
                textOf(listStrategy.handle(1L, admin, String.valueOf(FACTION_PN))));
    }

    /**
     * 提取单条文本回执内容。
     *
     * @param msgs 策略返回消息
     * @return 文本内容
     */
    private String textOf(List<? extends QqMsgParam<?>> msgs) {
        return ((TextQqMsg) msgs.getFirst()).getData().text();
    }

    /**
     * 构造指定QQ的发送人。
     */
    private QqRecMsgSender sender(long qq) {
        QqRecMsgSender sender = new QqRecMsgSender();
        sender.setUserId(qq);
        return sender;
    }

    /**
     * 桩发送者QQ到绑定帮派。
     */
    private void stubSenderFaction(long qq, long factionId) {
        TornUserDO user = new TornUserDO();
        user.setId(2001L);
        user.setFactionId(factionId);
        when(userManager.getUserByQq(qq)).thenReturn(user);
    }

    /**
     * 构造帮派简称映射桩数据。
     */
    private Map<Long, TornSettingFactionDO> factionShortNameMap() {
        TornSettingFactionDO bsu = new TornSettingFactionDO();
        bsu.setId(FACTION_BSU);
        bsu.setFactionShortName("BSU");
        TornSettingFactionDO phn = new TornSettingFactionDO();
        phn.setId(FACTION_PN);
        phn.setFactionShortName("PHN");
        return Map.of(FACTION_BSU, bsu, FACTION_PN, phn);
    }
}
