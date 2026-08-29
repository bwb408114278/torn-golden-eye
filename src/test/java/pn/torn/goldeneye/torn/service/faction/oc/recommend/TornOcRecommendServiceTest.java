package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.TornConstants;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.recommend.TornOcRecommendManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcSlotDictBO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OC推荐服务单元测试 —— 验证大锅饭模式下当前队的豁免逻辑
 *
 * @author Bai
 * @version 1.5.1
 * @since 2026.06.29
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC推荐逻辑测试")
class TornOcRecommendServiceTest {
    private static final long FACTION_HP = 2095L;
    private static final long USER_ID = 100L;
    private static final String OC_ACE = TornConstants.OC_NAME_ACE_IN_THE_HOLE;
    private static final String OC_BREAK_BANK = TornConstants.OC_NAME_BREAK_THE_BANK;

    @Mock
    private TornOcRecommendManager ocRecommendManager;
    @Mock
    private TornFactionOcDAO ocDao;
    @Mock
    private TornFactionOcSlotDAO ocSlotDao;
    @Mock
    private TornFactionOcUserDAO ocUserDao;

    @InjectMocks
    private TornOcRecommendService recommendService;

    private TornUserDO user;
    private TornFactionOcDO aceOc;
    private TornFactionOcDO breakBankOc;
    private TornFactionOcSlotDO aceSlot;
    private TornFactionOcSlotDO breakBankSlot;
    private OcSlotDictBO joinedOc;

    @BeforeEach
    void setUp() {
        // 用户在HP帮派
        user = new TornUserDO();
        user.setId(USER_ID);
        user.setFactionId(FACTION_HP);

        // 当前队伍：Ace in the Hole（非轮转OC），空转中
        aceOc = buildOc(1L, OC_ACE, 5, LocalDateTime.now().plusHours(3));
        aceSlot = new TornFactionOcSlotDO();
        aceSlot.setOcId(1L);
        aceSlot.setUserId(USER_ID);
        aceSlot.setPosition("Engineer#1");
        aceSlot.setProgress(BigDecimal.ZERO);
        joinedOc = new OcSlotDictBO(aceOc, aceSlot);

        // 另一个队伍：Break the Bank（轮转OC）
        breakBankOc = buildOc(2L, OC_BREAK_BANK, 5, LocalDateTime.now().plusHours(6));
        breakBankSlot = new TornFactionOcSlotDO();
        breakBankSlot.setOcId(2L);
        breakBankSlot.setUserId(null);                   // 空闲槽位
        breakBankSlot.setPosition("Engineer#1");
    }

    // ========================================================
    // 核心场景：空转在非轮转OC — 当前队应参与评估
    // ========================================================

    @Test
    @DisplayName("空转在非轮转OC → 当前岗位不作为推荐结果")
    void idlingInNonRotationOc_shouldExcludeCurrentOc() {
        // Given: 候选列表包含当前队和另一个轮转队
        when(ocDao.queryRecrutList(FACTION_HP)).thenReturn(new java.util.ArrayList<>(List.of(breakBankOc)));

        // 空槽位不包含当前岗位
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(List.of(breakBankSlot)));

        // 用户成功率：满足Break the Bank（触发大锅饭）
        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_BREAK_BANK);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));

        // checkIsReassignRecommended → true（用户有轮转OC成功率）
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);
        when(ocRecommendManager.isOcDisabled(anyLong(), any())).thenReturn(false);

        // findSlotSetting → 正常返回
        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setSlotShortCode("Engineer#1");
        slotSetting.setPassRate(60);
        slotSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(slotSetting);
        when(ocRecommendManager.findSlotRequirement(anyLong(), any(), any())).thenReturn(slotSetting);

        // findUserPassRate → 正常匹配
        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);

        // calcRecommendScore → 当前队基线85，Break the Bank候选严格更高
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    TornFactionOcDO oc = inv.getArgument(1);
                    return oc.getId() == 1L ? BigDecimal.valueOf(85) : BigDecimal.valueOf(90);
                });
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("即将停转");

        // When
        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        // Then: 当前岗位不作为可切换推荐结果
        assertThat(result).hasSize(1);
        assertThat(result).extracting(OcRecommendationVO::getOcName)
                .containsExactly(OC_BREAK_BANK);
    }

    // ========================================================
    // 基线过滤：评分低于当前队的队伍不展示
    // ========================================================

    @Test
    @DisplayName("当前队评分最高 → 不展示当前岗位")
    void currentTeamHasHighestScore_onlyCurrentTeamReturned() {
        when(ocDao.queryRecrutList(FACTION_HP)).thenReturn(new java.util.ArrayList<>(List.of(breakBankOc)));
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(List.of(breakBankSlot)));

        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_BREAK_BANK);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);
        when(ocRecommendManager.isOcDisabled(anyLong(), any())).thenReturn(false);

        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setSlotShortCode("Engineer#1");
        slotSetting.setPassRate(60);
        slotSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(slotSetting);
        when(ocRecommendManager.findSlotRequirement(anyLong(), any(), any())).thenReturn(slotSetting);

        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);

        // 当前队(Ace)评分90，别队(Break Bank)评分85
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    TornFactionOcDO oc = inv.getArgument(1);
                    return oc.getId() == 1L ? BigDecimal.valueOf(90) : BigDecimal.valueOf(85);
                });
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("即将停转");

        // When
        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        // Then: 别队低于当前岗位基线，当前岗位也不进入推荐结果
        assertThat(result).isEmpty();
    }

    // ========================================================
    // 对照场景：未加入任何OC — 行为不变
    // ========================================================

    @Test
    @DisplayName("未加入任何OC → 仍然只看轮转OC")
    void notJoined_shouldOnlySeeRotationOcs() {
        when(ocDao.queryRecrutList(FACTION_HP)).thenReturn(new java.util.ArrayList<>(List.of(breakBankOc)));
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(List.of(breakBankSlot)));

        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_BREAK_BANK);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);

        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setSlotShortCode("Engineer#1");
        slotSetting.setPassRate(60);
        slotSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(slotSetting);

        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(85));
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("高成功率");

        // When: joinedOc = null
        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, null);

        // Then: 只有轮转OC
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getOcName()).isEqualTo(OC_BREAK_BANK);
    }

    // ========================================================
    // 同分排序：缺人少的OC优先
    // ========================================================

    @Test
    @DisplayName("评分相同的OC → 缺人少的排在缺人多的前面")
    void sameScore_shouldPreferOcWithFewerEmptySlots() {
        // Given: 缺人多的Break Bank（缺3人）按停转时间升序排在候选列表前面
        when(ocDao.queryRecrutList(FACTION_HP))
                .thenReturn(new java.util.ArrayList<>(List.of(breakBankOc, aceOc)));
        TornFactionOcSlotDO breakBankSlot2 = new TornFactionOcSlotDO();
        breakBankSlot2.setOcId(breakBankOc.getId());
        breakBankSlot2.setUserId(null);
        breakBankSlot2.setPosition("Engineer#2");
        TornFactionOcSlotDO breakBankSlot3 = new TornFactionOcSlotDO();
        breakBankSlot3.setOcId(breakBankOc.getId());
        breakBankSlot3.setUserId(null);
        breakBankSlot3.setPosition("Engineer#3");
        TornFactionOcSlotDO aceIdleSlot = new TornFactionOcSlotDO();
        aceIdleSlot.setOcId(aceOc.getId());
        aceIdleSlot.setUserId(null);
        aceIdleSlot.setPosition("Engineer#2");
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(
                List.of(breakBankSlot, breakBankSlot2, breakBankSlot3, aceIdleSlot)));

        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_BREAK_BANK);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));
        // 未入队且非大锅饭，避免轮转过滤
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(false);

        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setSlotShortCode("Engineer#1");
        slotSetting.setPassRate(60);
        slotSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(slotSetting);

        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);
        // 两队评分相同，只有缺人数不同：Break Bank缺3人，Ace缺1人
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(85));
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("高成功率");

        // When: joinedOc = null
        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, null);

        // Then: 缺1人的Ace排在缺3人的Break Bank前面
        assertThat(result).extracting(OcRecommendationVO::getOcId)
                .containsExactly(aceOc.getId(), breakBankOc.getId(), breakBankOc.getId());
    }

    // ========================================================
    // 同分基线：按（评分, 缺人数）字典序过滤，避免同分来回切换
    // ========================================================

    @Test
    @DisplayName("同分且候选OC缺人更少 → 推荐候选，同队同分岗位不推荐")
    void equalScoreCandidateFewerEmpty_shouldRecommendOnlyCandidateOc() {
        // Given: 当前队Ace另有2个空位，Break the Bank缺1人
        when(ocDao.queryRecrutList(FACTION_HP))
                .thenReturn(new java.util.ArrayList<>(List.of(breakBankOc, aceOc)));
        TornFactionOcSlotDO aceEmptySlot1 = buildEmptySlot(aceOc.getId(), "Engineer#2");
        TornFactionOcSlotDO aceEmptySlot2 = buildEmptySlot(aceOc.getId(), "Engineer#3");
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(
                List.of(aceEmptySlot1, aceEmptySlot2, breakBankSlot)));

        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_BREAK_BANK);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);
        when(ocRecommendManager.isOcDisabled(anyLong(), any())).thenReturn(false);

        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setSlotShortCode("Engineer#1");
        slotSetting.setPassRate(60);
        slotSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(slotSetting);
        when(ocRecommendManager.findSlotRequirement(anyLong(), any(), any())).thenReturn(slotSetting);

        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);
        // 当前岗位与全部候选岗位评分相同
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(90));
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("高成功率");

        // When
        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        // Then: Break the Bank缺1人 < 当前队缺2人 → 推荐；同队Ace空位缺人数相同 → 不推荐
        assertThat(result).extracting(OcRecommendationVO::getOcId)
                .containsExactly(breakBankOc.getId());
    }

    @Test
    @DisplayName("同分且候选OC缺人不少于当前队 → 不再推荐，避免同分来回切换")
    void equalScoreCandidateNotFewerEmpty_shouldFilterOut() {
        // Given: 当前队Ace另有1个空位，Break the Bank缺2人，全部候选与当前岗位同分
        when(ocDao.queryRecrutList(FACTION_HP))
                .thenReturn(new java.util.ArrayList<>(List.of(breakBankOc, aceOc)));
        TornFactionOcSlotDO breakBankSlot2 = buildEmptySlot(breakBankOc.getId(), "Engineer#2");
        TornFactionOcSlotDO aceEmptySlot = buildEmptySlot(aceOc.getId(), "Engineer#2");
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(
                List.of(breakBankSlot, breakBankSlot2, aceEmptySlot)));

        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_BREAK_BANK);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);
        when(ocRecommendManager.isOcDisabled(anyLong(), any())).thenReturn(false);

        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setSlotShortCode("Engineer#1");
        slotSetting.setPassRate(60);
        slotSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(slotSetting);
        when(ocRecommendManager.findSlotRequirement(anyLong(), any(), any())).thenReturn(slotSetting);

        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(90));
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("高成功率");

        // When
        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        // Then: 同分候选缺人数不少于当前队 → 全部过滤
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("同队候选评分严格更高 → 仍然推荐换位")
    void sameOcCandidateStrictlyHigher_shouldRecommend() {
        // Given: 当前队Ace可换岗位评分高于当前岗位
        when(ocDao.queryRecrutList(FACTION_HP)).thenReturn(new java.util.ArrayList<>(List.of(aceOc)));
        TornFactionOcSlotDO aceEmptySlot = buildEmptySlot(aceOc.getId(), "Engineer#2");
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(List.of(aceEmptySlot)));

        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_ACE);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);
        when(ocRecommendManager.isOcDisabled(anyLong(), any())).thenReturn(false);

        TornSettingOcSlotDO currentRequirement = new TornSettingOcSlotDO();
        currentRequirement.setSlotShortCode("Engineer#1");
        currentRequirement.setPassRate(60);
        currentRequirement.setPriority(15);
        TornSettingOcSlotDO candidateSetting = new TornSettingOcSlotDO();
        candidateSetting.setSlotShortCode("Engineer#2");
        candidateSetting.setPassRate(60);
        candidateSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(candidateSetting);
        when(ocRecommendManager.findSlotRequirement(anyLong(), any(), any())).thenReturn(currentRequirement);

        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);
        // 当前岗位评分85，同队候选评分90，严格更高不受缺人数并列影响
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    TornSettingOcSlotDO setting = inv.getArgument(2);
                    return "Engineer#1".equals(setting.getSlotShortCode())
                            ? BigDecimal.valueOf(85) : BigDecimal.valueOf(90);
                });
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("高成功率");

        // When
        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        // Then: 严格更高分的同队候选仍然推荐
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getRecommendedPosition()).isEqualTo("Engineer#2");
    }

    @Test
    @DisplayName("空转当前队基线按停转时间减一天计算且不改动共享OC对象")
    void idleCurrentOcBaseline_shouldUseMinusOneDayReadyTimeWithoutMutation() {
        // Given: 当前队Ace不在招募列表（Planning状态走复制分支），Break the Bank缺1人
        LocalDateTime originalReadyTime = aceOc.getReadyTime();
        when(ocDao.queryRecrutList(FACTION_HP)).thenReturn(new java.util.ArrayList<>(List.of(breakBankOc)));
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(List.of(breakBankSlot)));

        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_BREAK_BANK);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);
        when(ocRecommendManager.isOcDisabled(anyLong(), any())).thenReturn(false);

        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setSlotShortCode("Engineer#1");
        slotSetting.setPassRate(60);
        slotSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(slotSetting);
        when(ocRecommendManager.findSlotRequirement(anyLong(), any(), any())).thenReturn(slotSetting);

        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(90));
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("高成功率");

        // When
        recommendService.recommendOcForUser(user, 3, joinedOc);

        // Then: 基线在候选评估前计算，首次评分调用即当前岗位基线，口径为停转时间减一天
        ArgumentCaptor<TornFactionOcDO> ocCaptor = ArgumentCaptor.forClass(TornFactionOcDO.class);
        verify(ocRecommendManager, atLeastOnce())
                .calcRecommendScore(anyBoolean(), ocCaptor.capture(), any(), any());
        assertThat(ocCaptor.getAllValues().getFirst().getReadyTime())
                .isEqualTo(originalReadyTime.minusDays(1));
        // 共享的当前队对象保持原口径，不因推荐流程被扣减
        assertThat(aceOc.getReadyTime()).isEqualTo(originalReadyTime);
    }

    // ========================================================
    // 禁用当前OC且有进度的推荐范围与基线
    // ========================================================

    @Test
    @DisplayName("禁用当前OC且有进度 → 返回正常OC推荐且当前禁用OC不作为候选")
    void disabledCurrentOcWithProgress_shouldRecommendNormalOcAndExcludeDisabledOc() {
        aceSlot.setProgress(BigDecimal.ONE);
        TornFactionOcSlotDO normalSlot = new TornFactionOcSlotDO();
        normalSlot.setOcId(2L);
        normalSlot.setUserId(null);
        normalSlot.setPosition("Engineer#2");
        when(ocDao.queryRecrutList(FACTION_HP)).thenReturn(new java.util.ArrayList<>(List.of(breakBankOc)));
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(List.of(normalSlot)));

        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_BREAK_BANK);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);
        when(ocRecommendManager.isOcDisabled(anyLong(), any())).thenReturn(true);

        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setSlotShortCode("Engineer#1");
        slotSetting.setPassRate(60);
        slotSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(slotSetting);
        when(ocRecommendManager.findSlotRequirement(anyLong(), any(), any())).thenReturn(slotSetting);

        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(85));
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("高成功率");

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        assertThat(result).hasSize(1);
        assertThat(result).extracting(OcRecommendationVO::getOcName)
                .containsExactly(OC_BREAK_BANK);
        assertThat(result).extracting(OcRecommendationVO::getOcId)
                .doesNotContain(aceOc.getId());
        assertThat(result).extracting(OcRecommendationVO::getRecommendedPosition)
                .doesNotContain(aceSlot.getPosition());
    }

    @Test
    @DisplayName("禁用当前OC且有进度 → 当前禁用OC评分不作为正常OC推荐基线")
    void disabledCurrentOcWithProgress_shouldIgnoreDisabledOcScoreBaseline() {
        aceSlot.setProgress(BigDecimal.ONE);
        when(ocDao.queryRecrutList(FACTION_HP)).thenReturn(new java.util.ArrayList<>(List.of(breakBankOc)));
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(List.of(breakBankSlot)));

        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_BREAK_BANK);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);
        when(ocRecommendManager.isOcDisabled(anyLong(), any())).thenReturn(true);

        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setSlotShortCode("Engineer#1");
        slotSetting.setPassRate(60);
        slotSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(slotSetting);
        when(ocRecommendManager.findSlotRequirement(anyLong(), any(), any())).thenReturn(slotSetting);

        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);
        // 禁用当前OC评分100，正常OC评分85；若仍按当前评分过滤会得到空结果
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    TornFactionOcDO oc = inv.getArgument(1);
                    return oc.getId() == 1L ? BigDecimal.valueOf(100) : BigDecimal.valueOf(85);
                });
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("高成功率");

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getOcName()).isEqualTo(OC_BREAK_BANK);
    }

    @Test
    @DisplayName("禁用当前OC且有进度 → 正常OC无符合成功率岗位时返回空且不抛异常")
    void disabledCurrentOcWithProgress_noQualifiedNormalOc_shouldReturnEmpty() {
        aceSlot.setProgress(BigDecimal.ONE);
        when(ocDao.queryRecrutList(FACTION_HP)).thenReturn(new java.util.ArrayList<>(List.of(breakBankOc)));
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(List.of(breakBankSlot)));

        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_BREAK_BANK);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);
        when(ocRecommendManager.isOcDisabled(anyLong(), any())).thenReturn(true);

        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setSlotShortCode("Engineer#1");
        slotSetting.setPassRate(60);
        slotSetting.setPriority(15);
        when(ocRecommendManager.findSlotRequirement(anyLong(), any(), any())).thenReturn(slotSetting);
        // 正常OC岗位配置为空，等价于没有符合成功率要求的岗位
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(null);

        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(85));

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("当前OC未禁用且有进度 → 低于当前评分的同OC岗位仍不展示")
    void joinedOcWithProgressNotDisabled_shouldKeepCurrentBaselineFilter() {
        aceSlot.setProgress(BigDecimal.ONE);
        TornFactionOcSlotDO aceEmptySlot = new TornFactionOcSlotDO();
        aceEmptySlot.setOcId(aceOc.getId());
        aceEmptySlot.setUserId(null);
        aceEmptySlot.setPosition("Engineer#2");
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(List.of(aceEmptySlot)));

        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_ACE);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);
        when(ocRecommendManager.isOcDisabled(anyLong(), any())).thenReturn(false);

        TornSettingOcSlotDO currentRequirement = new TornSettingOcSlotDO();
        currentRequirement.setSlotShortCode("Engineer#1");
        currentRequirement.setPassRate(60);
        currentRequirement.setPriority(15);
        TornSettingOcSlotDO candidateSetting = new TornSettingOcSlotDO();
        candidateSetting.setSlotShortCode("Engineer#2");
        candidateSetting.setPassRate(60);
        candidateSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(candidateSetting);
        when(ocRecommendManager.findSlotRequirement(anyLong(), any(), any())).thenReturn(currentRequirement);

        TornFactionOcUserDO matched = new TornFactionOcUserDO();
        matched.setPassRate(70);
        when(ocRecommendManager.findUserPassRate(anyList(), any(), any())).thenReturn(matched);
        // 当前岗位评分90，同OC可换岗位评分85，低于基线不展示
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    TornSettingOcSlotDO setting = inv.getArgument(2);
                    return "Engineer#1".equals(setting.getSlotShortCode())
                            ? BigDecimal.valueOf(90) : BigDecimal.valueOf(85);
                });
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("高成功率");

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        assertThat(result).isEmpty();
    }

    // ========================================================
    // 工具方法
    // ========================================================

    private TornFactionOcDO buildOc(long id, String name, int rank, LocalDateTime readyTime) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setFactionId(FACTION_HP);
        oc.setName(name);
        oc.setRank(rank);
        oc.setReadyTime(readyTime);
        return oc;
    }

    private TornFactionOcSlotDO buildEmptySlot(long ocId, String position) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        slot.setUserId(null);
        slot.setPosition(position);
        return slot;
    }
}
