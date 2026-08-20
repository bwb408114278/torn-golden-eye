package pn.torn.goldeneye.torn.service.faction.oc;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.base.bot.Bot;
import pn.torn.goldeneye.base.bot.BotHttpReqParam;
import pn.torn.goldeneye.constants.torn.enums.TornOcStatusEnum;
import pn.torn.goldeneye.napcat.send.msg.GroupMsgReqParam;
import pn.torn.goldeneye.napcat.send.msg.param.AtQqMsg;
import pn.torn.goldeneye.napcat.send.msg.param.TextQqMsg;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingFactionDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.recommend.TornOcRecommendManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingFactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * OC巡检提醒服务测试，验证活动OC扫描、问题分类、成功率缺失和消息@去重。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC巡检提醒服务测试")
class OcBannedNoticeServiceTest {
    private static final long FACTION_ID = 2095L;
    private static final long USER_ID = 100L;

    @Mock
    private Bot bot;
    @Mock
    private TornFactionOcDAO ocDao;
    @Mock
    private TornFactionOcSlotDAO slotDao;
    @Mock
    private TornFactionOcUserDAO ocUserDao;
    @Mock
    private TornUserDAO userDao;
    @Mock
    private TornSettingFactionManager settingFactionManager;
    @Mock
    private TornOcRecommendManager ocRecommendManager;

    private OcBannedNoticeService noticeService;
    private TornSettingFactionDO faction;

    @BeforeEach
    void setUp() {
        noticeService = new OcBannedNoticeService(bot, ocDao, slotDao, ocUserDao, userDao,
                settingFactionManager, ocRecommendManager);
        faction = new TornSettingFactionDO();
        faction.setId(FACTION_ID);
        faction.setGroupId(123456L);
        faction.setOcCommanderIds("3001");
    }

    @Test
    @DisplayName("Recruiting和Planning活动OC均扫描且同一用户只@一次")
    void activeOcs_shouldScanBothStatusesAndMentionUserOnce() {
        when(settingFactionManager.getList()).thenReturn(List.of(faction));
        TornFactionOcDO disabledOc = buildOc(1L, TornOcStatusEnum.RECRUITING.getCode(), "Disabled OC");
        TornFactionOcDO insufficientOc = buildOc(2L, TornOcStatusEnum.PLANNING.getCode(), "Insufficient OC");
        TornFactionOcSlotDO disabledSlot = buildSlot(11L, disabledOc.getId(), USER_ID, "Engineer#1");
        TornFactionOcSlotDO insufficientSlot = buildSlot(12L, insufficientOc.getId(), USER_ID, "Engineer#1");
        mockActiveOcs(List.of(disabledOc, insufficientOc), List.of(disabledSlot, insufficientSlot));
        TornUserDO user = buildUser();
        when(userDao.queryUserMap(List.of(USER_ID))).thenReturn(Map.of(USER_ID, user));
        when(ocUserDao.queryByFactionIdAndUserIds(FACTION_ID, List.of(USER_ID)))
                .thenReturn(List.of(passRate(USER_ID, "Disabled OC", 5, "Engineer", 80),
                        passRate(USER_ID, "Insufficient OC", 5, "Engineer", 50)));
        when(ocRecommendManager.isOcDisabled(FACTION_ID, disabledOc)).thenReturn(true);
        when(ocRecommendManager.isOcDisabled(FACTION_ID, insufficientOc)).thenReturn(false);
        when(ocRecommendManager.findSlotRequirement(FACTION_ID, disabledOc, disabledSlot)).thenReturn(requirement(60));
        when(ocRecommendManager.findSlotRequirement(FACTION_ID, insufficientOc, insufficientSlot)).thenReturn(requirement(60));
        when(bot.sendRequest(any(BotHttpReqParam.class), eq(String.class))).thenReturn(null);

        noticeService.checkAndNotice(LocalDateTime.of(2026, 8, 20, 12, 0));

        GroupMsgReqParam message = captureMessage();
        assertThat(message.getMessage().stream().filter(AtQqMsg.class::isInstance)).hasSize(2);
        List<String> texts = message.getMessage().stream()
                .filter(TextQqMsg.class::isInstance)
                .map(TextQqMsg.class::cast)
                .map(item -> item.getData().text())
                .toList();
        assertThat(texts)
                .anyMatch(text -> text.contains("Disabled OC") && text.contains("OC已禁用"))
                .anyMatch(text -> text.contains("Insufficient OC") && text.contains("当前50%，要求60%"));
        verify(ocUserDao).queryByFactionIdAndUserIds(FACTION_ID, List.of(USER_ID));
    }

    @Test
    @DisplayName("成功率缺失不伪造成成功率不足且禁用问题仍提醒")
    void missingPassRate_shouldNotBeReportedAsInsufficient() {
        when(settingFactionManager.getList()).thenReturn(List.of(faction));
        TornFactionOcDO normalOc = buildOc(1L, TornOcStatusEnum.RECRUITING.getCode(), "Normal OC");
        TornFactionOcSlotDO slot = buildSlot(11L, normalOc.getId(), USER_ID, "Engineer#1");
        mockActiveOcs(List.of(normalOc), List.of(slot));
        when(userDao.queryUserMap(List.of(USER_ID))).thenReturn(Map.of(USER_ID, buildUser()));
        when(ocUserDao.queryByFactionIdAndUserIds(FACTION_ID, List.of(USER_ID))).thenReturn(List.of());
        when(ocRecommendManager.isOcDisabled(FACTION_ID, normalOc)).thenReturn(false);
        when(ocRecommendManager.findSlotRequirement(FACTION_ID, normalOc, slot)).thenReturn(requirement(60));

        noticeService.checkAndNotice(LocalDateTime.of(2026, 8, 20, 12, 0));

        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    @Test
    @DisplayName("静默时段不扫描也不发送消息")
    void quietHours_shouldSkipInspection() {
        noticeService.checkAndNotice(LocalDateTime.of(2026, 8, 20, 5, 59));

        verifyNoInteractions(ocDao, slotDao, ocUserDao, userDao);
        verify(bot, never()).sendRequest(any(BotHttpReqParam.class), eq(String.class));
    }

    private void mockActiveOcs(List<TornFactionOcDO> ocs, List<TornFactionOcSlotDO> slots) {
        LambdaQueryChainWrapper<TornFactionOcDO> query = mock(LambdaQueryChainWrapper.class);
        when(ocDao.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.in(any(), any(Object[].class))).thenReturn(query);
        when(query.list()).thenReturn(ocs);
        when(slotDao.queryListByOc(ocs)).thenReturn(slots);
    }

    private TornFactionOcDO buildOc(long id, String status, String name) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setFactionId(FACTION_ID);
        oc.setName(name);
        oc.setRank(5);
        oc.setStatus(status);
        return oc;
    }

    private TornFactionOcSlotDO buildSlot(long id, long ocId, long userId, String position) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setId(id);
        slot.setOcId(ocId);
        slot.setUserId(userId);
        slot.setPosition(position);
        return slot;
    }

    private TornFactionOcUserDO passRate(long userId, String ocName, int rank,
                                         String position, int rate) {
        TornFactionOcUserDO data = new TornFactionOcUserDO();
        data.setUserId(userId);
        data.setFactionId(FACTION_ID);
        data.setOcName(ocName);
        data.setRank(rank);
        data.setPosition(position);
        data.setPassRate(rate);
        return data;
    }

    private TornSettingOcSlotDO requirement(int passRate) {
        TornSettingOcSlotDO requirement = new TornSettingOcSlotDO();
        requirement.setPassRate(passRate);
        return requirement;
    }

    private TornUserDO buildUser() {
        TornUserDO user = new TornUserDO();
        user.setId(USER_ID);
        user.setNickname("测试用户");
        user.setQqId(2001L);
        return user;
    }

    private GroupMsgReqParam captureMessage() {
        ArgumentCaptor<BotHttpReqParam> captor = ArgumentCaptor.forClass(BotHttpReqParam.class);
        verify(bot).sendRequest(captor.capture(), eq(String.class));
        return (GroupMsgReqParam) captor.getValue().body();
    }
}
