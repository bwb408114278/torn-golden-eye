package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.when;

/**
 * OC推荐服务单元测试 —— 验证大锅饭模式下当前队的豁免逻辑
 *
 * @author Bai
 * @version 1.3.6
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

        // calcRecommendScore → 返回评分
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(90));
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
}
