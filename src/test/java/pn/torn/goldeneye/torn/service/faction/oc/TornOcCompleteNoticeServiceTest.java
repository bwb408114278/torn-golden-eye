package pn.torn.goldeneye.torn.service.faction.oc;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeSlotVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeUserVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionCrimeVO;
import pn.torn.goldeneye.torn.model.faction.crime.TornFactionOcVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberDTO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberListVO;
import pn.torn.goldeneye.torn.model.faction.member.TornFactionMemberVO;
import pn.torn.goldeneye.torn.model.user.TornUserStatusVO;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.TornOcAssignService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Torn OC完成通知服务测试
 *
 * @author Bai
 * @version 1.2.11
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

    @BeforeEach
    void setUp() {
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

        mockInitScheduling(faction, oc);
        mockNoticeExecution(faction, oc, user, buildMemberListResp(user.getId(),
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

        mockInitScheduling(faction, oc);
        mockNoticeExecution(faction, oc, user, null);

        runNoticeTask(faction);

        assertFalse(sentTexts().stream().anyMatch(text -> text.contains("在住院中")));
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
     * 提取所有已发送群消息中的文本内容
     */
    private List<String> sentTexts() {
        ArgumentCaptor<BotHttpReqParam> paramCaptor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot, atLeastOnce()).sendRequest(paramCaptor.capture(), eq(String.class));
        return paramCaptor.getAllValues().stream()
                .map(BotHttpReqParam::body)
                .filter(GroupMsgReqParam.class::isInstance)
                .map(GroupMsgReqParam.class::cast)
                .flatMap(body -> body.getMessage().stream())
                .filter(TextQqMsg.class::isInstance)
                .map(TextQqMsg.class::cast)
                .map(msg -> msg.getData().text())
                .toList();
    }

    /**
     * 模拟初始化时的OC调度查询
     */
    private void mockInitScheduling(TornSettingFactionDO faction, TornFactionOcDO oc) {
        List<Long> factionIdList = List.of(TornConstants.FACTION_PN_ID, TornConstants.FACTION_SH_ID,
                TornConstants.FACTION_HP_ID, TornConstants.FACTION_BSU_ID,
                TornConstants.FACTION_PTA_ID, TornConstants.FACTION_CCRC_ID);
        when(settingFactionManager.getIdMap()).thenReturn(factionIdList.stream()
                .collect(Collectors.toMap(id -> id, id -> faction)));
        LambdaQueryChainWrapper<TornFactionOcDO> query = mock(LambdaQueryChainWrapper.class);
        when(ocDao.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.orderByAsc(any(com.baomidou.mybatisplus.core.toolkit.support.SFunction.class))).thenReturn(query);
        when(query.list()).thenReturn(List.of(oc));
        doNothing().when(taskService).updateTask(anyString(), any(Runnable.class), any(LocalDateTime.class));
    }

    /**
     * 模拟通知执行链路
     */
    private void mockNoticeExecution(TornSettingFactionDO faction, TornFactionOcDO oc,
                                     TornUserDO user, TornFactionMemberListVO memberResp) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setUserId(user.getId());
        doNothing().when(ocRefreshManager).refreshOc(1, faction.getId());
        when(ocSlotDao.queryListByOc(List.of(oc))).thenReturn(List.of(slot));
        when(userDao.queryUserMap(List.of(user.getId()))).thenReturn(Map.of(user.getId(), user));
        when(msgManager.buildOcTable(anyString(), eq(List.of(oc)))).thenReturn("base64-image");
        when(tornApi.sendRequest(eq(faction.getId()), any(), eq(TornFactionOcVO.class)))
                .thenReturn(buildOcApiResp(oc, user.getId()));
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
     * 构建即将完成的OC
     */
    private TornFactionOcDO buildOc() {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(501L);
        oc.setFactionId(10L);
        oc.setName("测试OC");
        oc.setRank(8);
        oc.setStatus(TornOcStatusEnum.PLANNING.getCode());
        oc.setReadyTime(LocalDateTime.now().plusMinutes(10));
        oc.setHasNoticed(false);
        return oc;
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
     * 构建OC接口响应
     */
    private TornFactionOcVO buildOcApiResp(TornFactionOcDO oc, Long userId) {
        TornFactionCrimeUserVO crimeUser = new TornFactionCrimeUserVO();
        crimeUser.setId(userId);
        TornFactionCrimeSlotVO crimeSlot = new TornFactionCrimeSlotVO();
        crimeSlot.setUser(crimeUser);
        TornFactionCrimeVO crime = new TornFactionCrimeVO();
        crime.setId(oc.getId());
        crime.setSlots(List.of(crimeSlot));
        TornFactionOcVO resp = new TornFactionOcVO();
        resp.setCrimes(List.of(crime));
        return resp;
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
