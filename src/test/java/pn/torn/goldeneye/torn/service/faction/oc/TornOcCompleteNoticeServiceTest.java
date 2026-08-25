package pn.torn.goldeneye.torn.service.faction.oc;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.base.torn.TornApi;
import pn.torn.goldeneye.configuration.DynamicTaskService;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.user.TornUserStatusEnum;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgReqParam;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.QqMsgParam;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.TornFactionOcRefreshManager;
import pn.torn.goldeneye.torn.manager.faction.crime.msg.TornFactionOcMsgManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;
import pn.torn.goldeneye.torn.manager.torn.TornItemsManager;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.torn.model.user.TornUserStatusVO;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcAssignService;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Torn OC完成通知服务测试
 *
 * @author Bai
 * @version 1.4.6
 * @since 2026.07.20
 */
@ExtendWith(MockitoExtension.class)
class TornOcCompleteNoticeServiceTest {
    @Mock
    private Bot bot;
    @Mock
    private TornApi tornApi;
    @Mock
    private DynamicTaskService taskService;
    @Mock
    private TornOcAssignService assignService;
    @Mock
    private TornFactionOcRefreshManager ocRefreshManager;
    @Mock
    private TornItemsManager itemsManager;
    @Mock
    private TornFactionOcMsgManager msgManager;
    @Mock
    private TornSettingFactionManager settingFactionManager;
    @Mock
    private TornFactionOcDAO ocDao;
    @Mock
    private TornFactionOcSlotDAO ocSlotDao;
    @Mock
    private TornFactionOcUserDAO ocUserDao;
    @Mock
    private TornUserDAO userDao;

    private TornOcCompleteNoticeService noticeService;
    private AtomicReference<List<TornFactionOcDO>> ocDaoListRef;

    @BeforeEach
    void setUp() {
        ocDaoListRef = new AtomicReference<>(List.of());
        noticeService = new TornOcCompleteNoticeService(bot, tornApi, taskService,
                assignService, ocRefreshManager, itemsManager, msgManager, settingFactionManager,
                ocDao, ocSlotDao, ocUserDao, userDao);
    }

    @Test
    @DisplayName("OC快完成时，成员异常状态应包含在指挥官提醒中")
    void shouldIncludeAbnormalStatusNotice() {
        TornSettingFactionDO faction = buildFaction();
        TornFactionOcDO oc = buildOc();
        TornUserDO user = buildUser();

        mockInitScheduling(faction, List.of(oc));
        mockNoticeExecution(faction, List.of(oc), user, buildMemberListResp(user.getId(),
                TornUserStatusEnum.HOSPITAL.getCode()));

        runNoticeTask(faction);

        assertTrue(sentTexts().stream()
                .anyMatch(text -> text.contains("测试用户") && text.contains("在住院中，请尽快出院")));
    }

    @Test
    @DisplayName("帮派成员接口返回空时，不生成异常状态提醒")
    void shouldSkipStatusWarning_whenMemberRespEmpty() {
        TornSettingFactionDO faction = buildFaction();
        TornFactionOcDO oc = buildOc();
        TornUserDO user = buildUser();

        mockInitScheduling(faction, List.of(oc));
        mockNoticeExecution(faction, List.of(oc), user, null);

        runNoticeTask(faction);

        assertFalse(sentTexts().stream().anyMatch(text -> text.contains("在住院中")));
    }

    @Test
    @DisplayName("消息组装：延误9分钟展示计划/实际分钟与延误约9分钟")
    void shouldFormatDelayDetail_whenDelayNineMinutes() {
        TornSettingFactionDO faction = buildFaction();
        TornUserDO user = buildUser();
        TornFactionOcDO oc = buildCompletedOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20),
                LocalDateTime.of(2026, 8, 1, 20, 30));

        sendCompleteNotice(faction, List.of(oc), List.of(user));

        List<String> texts = sentTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("以下OC完成时存在明显延误，请关注：")
                && text.contains("#8 Clinical Precision：计划20:21完成，实际20:30完成，延误约9分钟")));
        assertEquals(1L, sentAtQqIds().stream().filter(qq -> qq.equals(3001L)).count());
    }

    @Test
    @DisplayName("消息组装：延误4分钟和5分钟不提醒，延误6分钟提醒")
    void shouldUseDelayThreshold_overFiveMinutes() {
        TornSettingFactionDO faction = buildFaction();
        TornUserDO user = buildUser();

        sendCompleteNotice(faction, List.of(buildCompletedOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20),
                LocalDateTime.of(2026, 8, 1, 20, 25))), List.of(user));
        assertTrue(sentTexts().stream().noneMatch(text -> text.contains("以下OC完成时存在明显延误")));
        assertFalse(sentAtQqIds().contains(3001L));

        sendCompleteNotice(faction, List.of(buildCompletedOc(502L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20),
                LocalDateTime.of(2026, 8, 1, 20, 26))), List.of(user));
        assertTrue(sentTexts().stream().noneMatch(text -> text.contains("以下OC完成时存在明显延误")));
        assertFalse(sentAtQqIds().contains(3001L));

        sendCompleteNotice(faction, List.of(buildCompletedOc(503L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20),
                LocalDateTime.of(2026, 8, 1, 20, 27))), List.of(user));
        assertTrue(sentTexts().stream().anyMatch(text -> text.contains("以下OC完成时存在明显延误，请关注：")
                && text.contains("#8 Clinical Precision：计划20:21完成，实际20:27完成，延误约6分钟")));
        assertEquals(1L, sentAtQqIds().stream().filter(qq -> qq.equals(3001L)).count());
    }

    @Test
    @DisplayName("消息组装：同批多个明显延误OC合并为一条提醒且只@一次")
    void shouldMergeMultipleDelayedOcsInOneNotice() {
        TornSettingFactionDO faction = buildFaction();
        TornUserDO user = buildUser();
        TornFactionOcDO firstOc = buildCompletedOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20),
                LocalDateTime.of(2026, 8, 1, 20, 30));
        TornFactionOcDO secondOc = buildCompletedOc(502L, 7, "Window of Opportunity",
                LocalDateTime.of(2026, 8, 1, 20, 30),
                LocalDateTime.of(2026, 8, 1, 20, 42));

        sendCompleteNotice(faction, List.of(firstOc, secondOc), List.of(user));

        List<String> texts = sentTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("以下OC完成时存在明显延误，请关注：")
                && text.contains("#8 Clinical Precision：计划20:21完成，实际20:30完成，延误约9分钟")
                && text.contains("#7 Window of Opportunity：计划20:31完成，实际20:42完成，延误约11分钟")));
        assertEquals(1L, sentAtQqIds().stream().filter(qq -> qq.equals(3001L)).count());
        verify(bot, times(1)).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("真实完成检测：已完成OC延误9分钟时发送完成通知并@一次指挥官")
    void shouldSendDelayNoticeThroughCompleteCheck_whenOcCompletedLate() {
        TornSettingFactionDO faction = buildFaction();
        TornUserDO user = buildUser();
        TornFactionOcDO planningOc = buildOc();
        TornFactionOcDO completedOc = buildCompletedOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20),
                LocalDateTime.of(2026, 8, 1, 20, 30));

        mockInitScheduling(faction, List.of(planningOc));
        mockNoticeExecution(faction, List.of(planningOc), user, null);
        runNoticeTask(faction);
        runCompleteCheckTask(faction, List.of(completedOc),
                List.of(buildSlot(completedOc.getId(), user.getId())), List.of(user));

        assertEquals(1L, completionRequestCount());
        List<String> texts = completionTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("以下OC完成时存在明显延误，请关注：")
                && text.contains("#8 Clinical Precision：计划20:21完成，实际20:30完成，延误约9分钟")));
        assertTrue(completionAtQqIds().contains(2001L));
        assertEquals(1L, completionAtQqIds().stream().filter(qq -> qq.equals(3001L)).count());
    }

    @Test
    @DisplayName("真实完成检测：下一分钟正常完成不产生延误提醒")
    void shouldNotAppendDelayNoticeThroughCompleteCheck_whenCompletedOnPlannedMinute() {
        TornSettingFactionDO faction = buildFaction();
        TornUserDO user = buildUser();
        TornFactionOcDO planningOc = buildOc();
        TornFactionOcDO completedOc = buildCompletedOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20),
                LocalDateTime.of(2026, 8, 1, 20, 21));

        mockInitScheduling(faction, List.of(planningOc));
        mockNoticeExecution(faction, List.of(planningOc), user, null);
        runNoticeTask(faction);
        runCompleteCheckTask(faction, List.of(completedOc),
                List.of(buildSlot(completedOc.getId(), user.getId())), List.of(user));

        assertEquals(1L, completionRequestCount());
        List<String> texts = completionTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("#8 Clinical Precision 已完成")));
        assertTrue(texts.stream().noneMatch(text -> text.contains("以下OC完成时存在明显延误")));
        assertTrue(completionAtQqIds().contains(2001L));
        assertFalse(completionAtQqIds().contains(3001L));
        assertTrue(texts.stream().anyMatch(text -> text.contains("暂未适合加入的OC，联系OC指挥官生成")));
    }

    @Test
    @DisplayName("真实完成检测：秒数跨分钟但同一分钟桶执行完成时不误判延误")
    void shouldNotAppendDelayNoticeThroughCompleteCheck_whenSecondsCrossMinute() {
        TornSettingFactionDO faction = buildFaction();
        TornUserDO user = buildUser();
        TornFactionOcDO planningOc = buildOc();
        TornFactionOcDO completedOc = buildCompletedOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20, 45),
                LocalDateTime.of(2026, 8, 1, 20, 21, 10));

        mockInitScheduling(faction, List.of(planningOc));
        mockNoticeExecution(faction, List.of(planningOc), user, null);
        runNoticeTask(faction);
        runCompleteCheckTask(faction, List.of(completedOc),
                List.of(buildSlot(completedOc.getId(), user.getId())), List.of(user));

        assertEquals(1L, completionRequestCount());
        assertTrue(completionTexts().stream().noneMatch(text -> text.contains("以下OC完成时存在明显延误")));
        assertFalse(completionAtQqIds().contains(3001L));
    }

    @Test
    @DisplayName("真实完成检测：未完成OC不产生完成通知并继续轮询")
    void shouldNotSendCompleteNoticeThroughCompleteCheck_whenOcStillPlanning() {
        TornSettingFactionDO faction = buildFaction();
        TornUserDO user = buildUser();
        TornFactionOcDO planningOc = buildOc();

        mockInitScheduling(faction, List.of(planningOc));
        mockNoticeExecution(faction, List.of(planningOc), user, null);
        runNoticeTask(faction);
        runCompleteCheckTask(faction, List.of(planningOc), List.of(), List.of());

        assertEquals(0L, completionRequestCount());
        assertTrue(completionTexts().isEmpty());
        verify(taskService, atLeast(2)).updateTask(
                eq(faction.getFactionShortName() + "-oc-complete-check"),
                any(Runnable.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("真实完成检测：同一批已完成和未完成OC混合时只通知已完成OC")
    void shouldOnlyIncludeCompletedOcThroughCompleteCheck_whenMixedWithPlanning() {
        TornSettingFactionDO faction = buildFaction();
        TornUserDO user = buildUser();
        TornFactionOcDO oc1Planning = buildPlanningOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20));
        TornFactionOcDO oc2Planning = buildPlanningOc(502L, 7, "Window of Opportunity",
                LocalDateTime.of(2026, 8, 1, 20, 30));
        TornFactionOcDO oc1Completed = buildCompletedOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20),
                LocalDateTime.of(2026, 8, 1, 20, 30));
        TornFactionOcDO oc2Pending = buildPlanningOc(502L, 7, "Window of Opportunity",
                LocalDateTime.of(2026, 8, 1, 20, 30));

        mockInitScheduling(faction, List.of(oc1Planning, oc2Planning));
        mockNoticeExecution(faction, List.of(oc1Planning, oc2Planning), user, null);
        runNoticeTask(faction);
        runCompleteCheckTask(faction, List.of(oc1Completed, oc2Pending),
                List.of(buildSlot(oc1Completed.getId(), user.getId())), List.of(user));

        assertEquals(1L, completionRequestCount());
        List<String> texts = completionTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("#8 Clinical Precision 已完成")));
        assertTrue(texts.stream().noneMatch(text -> text.contains("Window of Opportunity")));
        assertTrue(texts.stream().anyMatch(text -> text.contains("以下OC完成时存在明显延误，请关注：")
                && text.contains("#8 Clinical Precision：计划20:21完成，实际20:30完成，延误约9分钟")));
        assertEquals(1L, completionAtQqIds().stream().filter(qq -> qq.equals(3001L)).count());
        verify(taskService, atLeast(2)).updateTask(
                eq(faction.getFactionShortName() + "-oc-complete-check"),
                any(Runnable.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("真实完成检测：完成时间为空或早于计划时不阻塞完成通知也不产生延误提醒")
    void shouldKeepCompletionNoticeWithoutDelay_whenTimeDataAbnormal() {
        TornSettingFactionDO faction = buildFaction();
        TornUserDO user = buildUser();
        List<TornFactionOcDO> planningList = List.of(
                buildPlanningOc(501L, 8, "Clinical Precision", LocalDateTime.of(2026, 8, 1, 20, 20)),
                buildPlanningOc(502L, 7, "Window of Opportunity", LocalDateTime.of(2026, 8, 1, 20, 30)),
                buildPlanningOc(503L, 6, "Sweep and Clear", LocalDateTime.of(2026, 8, 1, 20, 40)));
        List<TornFactionOcDO> abnormalCompletedList = List.of(
                buildCompletedOc(501L, 8, "Clinical Precision", null,
                        LocalDateTime.of(2026, 8, 1, 20, 30)),
                buildCompletedOc(502L, 7, "Window of Opportunity",
                        LocalDateTime.of(2026, 8, 1, 20, 30), null),
                buildCompletedOc(503L, 6, "Sweep and Clear",
                        LocalDateTime.of(2026, 8, 1, 20, 40),
                        LocalDateTime.of(2026, 8, 1, 20, 39)));

        mockInitScheduling(faction, planningList);
        mockNoticeExecution(faction, planningList, user, null);
        runNoticeTask(faction);
        runCompleteCheckTask(faction, abnormalCompletedList,
                List.of(buildSlot(501L, user.getId()), buildSlot(502L, user.getId()),
                        buildSlot(503L, user.getId())), List.of(user));

        assertEquals(1L, completionRequestCount());
        List<String> texts = completionTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("已完成，可以加入新的OC了")));
        assertTrue(texts.stream().noneMatch(text -> text.contains("以下OC完成时存在明显延误")));
        assertFalse(completionAtQqIds().contains(3001L));
    }

    @ParameterizedTest(name = "真实完成检测：指挥官配置[{0}]不阻塞完成通知也不产生延误@")
    @MethodSource("blankOrInvalidCommanderConfigs")
    @DisplayName("真实完成检测：指挥官配置为空或无效时不阻塞完成通知也不产生延误@")
    void shouldKeepCompletionNoticeWithoutDelay_whenCommanderConfigBlankOrInvalid(String commanderIds) {
        TornSettingFactionDO faction = buildFaction();
        faction.setOcCommanderIds(commanderIds);
        TornUserDO user = buildUser();
        TornFactionOcDO planningOc = buildOc();
        TornFactionOcDO completedOc = buildCompletedOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20),
                LocalDateTime.of(2026, 8, 1, 20, 30));

        mockInitScheduling(faction, List.of(planningOc));
        mockNoticeExecution(faction, List.of(planningOc), user, null);
        runNoticeTask(faction);
        runCompleteCheckTask(faction, List.of(completedOc),
                List.of(buildSlot(completedOc.getId(), user.getId())), List.of(user));

        assertEquals(1L, completionRequestCount());
        assertTrue(completionTexts().stream().anyMatch(text -> text.contains("以下OC完成时存在明显延误，请关注：")
                && text.contains("#8 Clinical Precision：计划20:21完成，实际20:30完成，延误约9分钟")));
        assertFalse(completionAtQqIds().contains(3001L));
    }

    @Test
    @DisplayName("启动时恢复已通知但未完成的OC完成检测")
    void shouldRestoreCompleteCheckTask_whenOcNoticedButNotComplete() {
        TornSettingFactionDO faction = buildFaction();
        TornUserDO user = buildUser();
        TornFactionOcDO noticedOc = buildPlanningOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20));
        noticedOc.setHasNoticed(true);
        TornFactionOcDO completedOc = buildCompletedOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20),
                LocalDateTime.of(2026, 8, 1, 20, 30));

        mockInitScheduling(faction, List.of());
        when(ocDao.queryNoticedNotCompleteByFaction(faction.getId()))
                .thenReturn(List.of(noticedOc));
        noticeService.init();

        runCompleteCheckTask(faction, List.of(completedOc),
                List.of(buildSlot(completedOc.getId(), user.getId())), List.of(user));

        assertEquals(1L, completionRequestCount());
        List<String> texts = completionTexts();
        assertTrue(texts.stream().anyMatch(text -> text.contains("#8 Clinical Precision 已完成")));
        assertTrue(texts.stream().anyMatch(text -> text.contains("以下OC完成时存在明显延误，请关注：")
                && text.contains("#8 Clinical Precision：计划20:21完成，实际20:30完成，延误约9分钟")));
        assertTrue(completionAtQqIds().contains(2001L));
    }

    @Test
    @DisplayName("刷新异常时任务链不中断")
    void shouldRescheduleCompleteCheckTask_whenRefreshThrows() {
        TornSettingFactionDO faction = buildFaction();
        TornUserDO user = buildUser();
        TornFactionOcDO planningOc = buildPlanningOc(501L, 8, "Clinical Precision",
                LocalDateTime.now().minusMinutes(10));
        TornFactionOcDO completedOc = buildCompletedOc(501L, 8, "Clinical Precision",
                LocalDateTime.of(2026, 8, 1, 20, 20),
                LocalDateTime.of(2026, 8, 1, 20, 30));

        mockInitScheduling(faction, List.of(planningOc));
        mockNoticeExecution(faction, List.of(planningOc), user, null);
        runNoticeTask(faction);

        doThrow(new RuntimeException("refresh failed"))
                .doNothing()
                .when(ocRefreshManager).refreshOc(1, faction.getId());

        ArgumentCaptor<Runnable> firstTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskService, atLeastOnce()).updateTask(
                eq(faction.getFactionShortName() + "-oc-complete-check"),
                firstTaskCaptor.capture(), any(LocalDateTime.class));
        firstTaskCaptor.getValue().run();

        assertEquals(0L, completionRequestCount());
        assertTrue(completionTexts().isEmpty());

        ArgumentCaptor<LocalDateTime> retryTimeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(taskService, atLeast(2)).updateTask(
                eq(faction.getFactionShortName() + "-oc-complete-check"),
                any(Runnable.class), retryTimeCaptor.capture());
        LocalDateTime retryTime = retryTimeCaptor.getAllValues().getLast();
        assertTrue(retryTime.isAfter(LocalDateTime.now().plusSeconds(30)));

        ocDaoListRef.set(List.of(completedOc));
        enableOcDaoInQuery();
        mockCompletedSlots(List.of(buildSlot(completedOc.getId(), user.getId())));
        mockCompleteNoticeData(faction, List.of(user));

        ArgumentCaptor<Runnable> secondTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskService, atLeast(2)).updateTask(
                eq(faction.getFactionShortName() + "-oc-complete-check"),
                secondTaskCaptor.capture(), any(LocalDateTime.class));
        secondTaskCaptor.getValue().run();

        assertEquals(1L, completionRequestCount());
        assertTrue(completionTexts().stream().anyMatch(text -> text.contains("以下OC完成时存在明显延误")));
    }

    @Test
    @DisplayName("启动时无已通知未完成OC不额外调度完成检测")
    void shouldNotScheduleCompleteCheck_whenNoNoticedNotCompleteOc() {
        TornSettingFactionDO faction = buildFaction();
        TornFactionOcDO planningOc = buildOc();

        mockInitScheduling(faction, List.of(planningOc));
        noticeService.init();

        verify(taskService, atLeastOnce()).updateTask(
                eq(faction.getFactionShortName() + "-oc-complete"),
                any(Runnable.class), any(LocalDateTime.class));
        verify(taskService, never()).updateTask(
                eq(faction.getFactionShortName() + "-oc-complete-check"),
                any(Runnable.class), any(LocalDateTime.class));
    }

    static Stream<Arguments> blankOrInvalidCommanderConfigs() {
        return Stream.of(Arguments.of(""), Arguments.of("abc"));
    }

    /**
     * 触发一次快完成通知任务
     */
    private void runNoticeTask(TornSettingFactionDO faction) {
        noticeService.init();
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskService, atLeastOnce()).updateTask(eq(faction.getFactionShortName() + "-oc-complete"),
                taskCaptor.capture(), any(LocalDateTime.class));
        taskCaptor.getValue().run();
    }

    /**
     * 通过已注册的完成检测任务执行 checkOcCompleted 真实入口。
     *
     * @param faction        帮派配置
     * @param currentOcList  完成检测时数据库返回的最新OC列表
     * @param completedSlots 已完成OC对应的岗位列表
     * @param completedUsers 已完成OC的参与成员
     */
    private void runCompleteCheckTask(TornSettingFactionDO faction, List<TornFactionOcDO> currentOcList,
                                      List<TornFactionOcSlotDO> completedSlots,
                                      List<TornUserDO> completedUsers) {
        ocDaoListRef.set(currentOcList);
        enableOcDaoInQuery();
        if (currentOcList.stream().anyMatch(oc -> TornOcStatusEnum.getCompleteStatusList().contains(oc.getStatus()))) {
            mockCompletedSlots(completedSlots);
        }
        if (!completedUsers.isEmpty()) {
            mockCompleteNoticeData(faction, completedUsers);
        }
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskService, atLeastOnce()).updateTask(
                eq(faction.getFactionShortName() + "-oc-complete-check"),
                taskCaptor.capture(), any(LocalDateTime.class));
        taskCaptor.getValue().run();
    }

    /**
     * 覆盖完成检测入口使用的OC查询Mock，按最新OC列表返回数据。
     */
    private void enableOcDaoInQuery() {
        LambdaQueryChainWrapper<TornFactionOcDO> query = mock(LambdaQueryChainWrapper.class);
        when(query.in(any(), anyCollection())).thenReturn(query);
        when(query.list()).thenAnswer(invocation -> ocDaoListRef.get());
        when(ocDao.lambdaQuery()).thenReturn(query);
    }

    /**
     * 直接调用私有发送OC完成通知方法，仅用于少量消息组装单元测试，不经过完成检测入口。
     */
    private void sendCompleteNotice(TornSettingFactionDO faction, List<TornFactionOcDO> ocList,
                                    List<TornUserDO> users) {
        List<Long> userIdList = users.stream().map(TornUserDO::getId).toList();
        mockCompleteNoticeData(faction, users);
        try {
            Method method = TornOcCompleteNoticeService.class.getDeclaredMethod(
                    "sendOcCompleteNotice", TornSettingFactionDO.class, List.class, List.class);
            method.setAccessible(true);
            method.invoke(noticeService, faction, userIdList, ocList);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("调用sendOcCompleteNotice失败", e);
        }
    }

    /**
     * 模拟初始化时的OC调度查询
     */
    private void mockInitScheduling(TornSettingFactionDO faction, List<TornFactionOcDO> ocList) {
        List<Long> factionIdList = List.of(TornConstants.FACTION_PN_ID, TornConstants.FACTION_SH_ID,
                TornConstants.FACTION_HP_ID, TornConstants.FACTION_BSU_ID,
                TornConstants.FACTION_PTA_ID, TornConstants.FACTION_CCRC_ID);
        when(settingFactionManager.getIdMap()).thenReturn(factionIdList.stream()
                .collect(Collectors.toMap(id -> id, id -> faction)));
        ocDaoListRef.set(ocList);
        LambdaQueryChainWrapper<TornFactionOcDO> query = mock(LambdaQueryChainWrapper.class);
        when(ocDao.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.orderByAsc(any(SFunction.class))).thenReturn(query);
        when(query.list()).thenAnswer(invocation -> ocDaoListRef.get());
        when(ocDao.queryNoticedNotCompleteByFaction(anyLong())).thenReturn(List.of());
        doNothing().when(taskService).updateTask(anyString(), any(Runnable.class), any(LocalDateTime.class));
    }

    /**
     * 模拟通知执行链路
     */
    private void mockNoticeExecution(TornSettingFactionDO faction, List<TornFactionOcDO> ocList,
                                     TornUserDO user, TornFactionMemberListVO memberResp) {
        List<TornFactionOcSlotDO> slots = user == null ? List.of()
                : List.of(buildSlot(ocList.getFirst().getId(), user.getId()));
        doNothing().when(ocRefreshManager).refreshOc(1, faction.getId());
        when(ocDao.queryNoticedNotCompleteByFaction(faction.getId())).thenReturn(ocList);
        when(ocSlotDao.queryListByOc(anyCollection())).thenReturn(slots);
        List<Long> userIds = slots.stream().map(TornFactionOcSlotDO::getUserId).distinct().toList();
        if (!userIds.isEmpty()) {
            when(userDao.queryUserMap(userIds)).thenReturn(Map.of(user.getId(), user));
        }
        when(msgManager.buildOcTable(anyString(), anyList())).thenReturn("base64-image");
        when(tornApi.sendRequest(eq(faction.getId()), any(), eq(TornFactionOcVO.class)))
                .thenReturn(new TornFactionOcVO());
        when(tornApi.sendRequest(any(TornFactionMemberDTO.class), eq(TornFactionMemberListVO.class)))
                .thenReturn(memberResp);
        when(ocDao.lambdaUpdate()).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            LambdaUpdateChainWrapper<TornFactionOcDO> wrapper = mock(LambdaUpdateChainWrapper.class);
            when(wrapper.set(any(), any())).thenReturn(wrapper);
            when(wrapper.in(any(), anyCollection())).thenReturn(wrapper);
            when(wrapper.update()).thenReturn(true);
            return wrapper;
        });
    }

    /**
     * 模拟完成通知所需的成员、推荐和Bot发送依赖。
     */
    private void mockCompleteNoticeData(TornSettingFactionDO faction, List<TornUserDO> users) {
        List<Long> userIdList = users.stream().map(TornUserDO::getId).toList();
        when(userDao.queryUserMap(userIdList)).thenReturn(users.stream()
                .collect(Collectors.toMap(TornUserDO::getId, user -> user)));
        when(ocUserDao.queryByUserId(userIdList)).thenReturn(List.of());
        when(assignService.assignUserList(eq(faction.getId()), any())).thenReturn(Map.of());
    }

    /**
     * 模拟完成检测中按已完成OC查询岗位列表。
     */
    private void mockCompletedSlots(List<TornFactionOcSlotDO> slots) {
        LambdaQueryChainWrapper<TornFactionOcSlotDO> query = mock(LambdaQueryChainWrapper.class);
        when(query.in(any(), anyCollection())).thenReturn(query);
        when(query.list()).thenReturn(slots);
        when(ocSlotDao.lambdaQuery()).thenReturn(query);
    }

    /**
     * 提取所有已发送群消息参数
     */
    private List<QqMsgParam<?>> sentMessages() {
        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot, atLeastOnce()).sendRequest(paramCaptor.capture(), eq(String.class));
        return paramCaptor.getAllValues().stream()
                .map(BotHttpReqParam::body)
                .filter(GroupMsgReqParam.class::isInstance)
                .map(GroupMsgReqParam.class::cast)
                .flatMap(body -> body.getMessage().stream())
                .toList();
    }

    /**
     * 提取所有已发送群消息中的文本内容
     */
    private List<String> sentTexts() {
        return sentMessages().stream()
                .filter(TextQqMsg.class::isInstance)
                .map(TextQqMsg.class::cast)
                .map(msg -> msg.getData().text())
                .toList();
    }

    /**
     * 提取所有已发送群消息中的At QQ号
     */
    private List<Long> sentAtQqIds() {
        return sentMessages().stream()
                .filter(AtQqMsg.class::isInstance)
                .map(AtQqMsg.class::cast)
                .map(msg -> msg.getData().qq())
                .toList();
    }

    /**
     * 提取实际OC完成通知对应的群消息参数，避免与“即将结束”的指挥官预告消息混淆。
     */
    private List<QqMsgParam<?>> completionMessages() {
        return sentMessagesFromRequestsContaining("已完成，可以加入新的OC了");
    }

    /**
     * 提取实际OC完成通知中的文本内容
     */
    private List<String> completionTexts() {
        return completionMessages().stream()
                .filter(TextQqMsg.class::isInstance)
                .map(TextQqMsg.class::cast)
                .map(msg -> msg.getData().text())
                .toList();
    }

    /**
     * 提取实际OC完成通知中的At QQ号
     */
    private List<Long> completionAtQqIds() {
        return completionMessages().stream()
                .filter(AtQqMsg.class::isInstance)
                .map(AtQqMsg.class::cast)
                .map(msg -> msg.getData().qq())
                .toList();
    }

    /**
     * 统计实际OC完成通知的Bot发送次数
     */
    private long completionRequestCount() {
        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot, atLeastOnce()).sendRequest(paramCaptor.capture(), eq(String.class));
        return paramCaptor.getAllValues().stream()
                .map(BotHttpReqParam::body)
                .filter(GroupMsgReqParam.class::isInstance)
                .map(GroupMsgReqParam.class::cast)
                .filter(body -> body.getMessage().stream()
                        .filter(TextQqMsg.class::isInstance)
                        .map(TextQqMsg.class::cast)
                        .map(msg -> msg.getData().text())
                        .anyMatch(text -> text.contains("已完成，可以加入新的OC了")))
                .count();
    }

    /**
     * 提取包含指定关键文本的群消息请求中的所有消息参数
     */
    private List<QqMsgParam<?>> sentMessagesFromRequestsContaining(String keyword) {
        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot, atLeastOnce()).sendRequest(paramCaptor.capture(), eq(String.class));
        return paramCaptor.getAllValues().stream()
                .map(BotHttpReqParam::body)
                .filter(GroupMsgReqParam.class::isInstance)
                .map(GroupMsgReqParam.class::cast)
                .filter(body -> body.getMessage().stream()
                        .filter(TextQqMsg.class::isInstance)
                        .map(TextQqMsg.class::cast)
                        .map(msg -> msg.getData().text())
                        .anyMatch(text -> text.contains(keyword)))
                .flatMap(body -> body.getMessage().stream())
                .toList();
    }

    /**
     * 构建测试帮派配置
     */
    private TornSettingFactionDO buildFaction() {
        TornSettingFactionDO faction = new TornSettingFactionDO();
        faction.setId(10L);
        faction.setGroupId(123456L);
        faction.setFactionShortName("PN");
        faction.setOcCommanderIds("3001");
        return faction;
    }

    /**
     * 构建已完成的OC（用于完成通知延误测试）
     */
    private TornFactionOcDO buildCompletedOc(Long id, int rank, String name,
                                             LocalDateTime readyTime, LocalDateTime executedTime) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setFactionId(10L);
        oc.setName(name);
        oc.setRank(rank);
        oc.setStatus(TornOcStatusEnum.SUCCESSFUL.getCode());
        oc.setReadyTime(readyTime);
        oc.setExecutedTime(executedTime);
        return oc;
    }

    /**
     * 构建计划中的OC
     */
    private TornFactionOcDO buildPlanningOc(Long id, int rank, String name, LocalDateTime readyTime) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setFactionId(10L);
        oc.setName(name);
        oc.setRank(rank);
        oc.setStatus(TornOcStatusEnum.PLANNING.getCode());
        oc.setReadyTime(readyTime);
        oc.setHasNoticed(false);
        return oc;
    }

    /**
     * 构建即将完成的OC
     */
    private TornFactionOcDO buildOc() {
        return buildPlanningOc(501L, 8, "测试OC", LocalDateTime.now().plusMinutes(10));
    }

    /**
     * 构建OC岗位
     */
    private TornFactionOcSlotDO buildSlot(Long ocId, Long userId) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setUserId(userId);
        return slot;
    }

    /**
     * 构建OC参与用户
     */
    private TornUserDO buildUser() {
        TornUserDO user = new TornUserDO();
        user.setId(1001L);
        user.setNickname("测试用户");
        user.setQqId(2001L);
        return user;
    }

    /**
     * 构建帮派成员接口响应
     */
    private TornFactionMemberListVO buildMemberListResp(long userId, String state) {
        TornUserStatusVO status = new TornUserStatusVO();
        status.setState(state);
        status.setDescription("desc");
        TornFactionMemberVO member = new TornFactionMemberVO();
        member.setId(userId);
        member.setStatus(status);
        TornFactionMemberListVO resp = new TornFactionMemberListVO();
        resp.setMembers(List.of(member));
        return resp;
    }
}
