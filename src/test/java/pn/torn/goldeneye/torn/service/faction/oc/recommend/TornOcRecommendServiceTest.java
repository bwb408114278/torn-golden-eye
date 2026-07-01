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
 * @version 1.2.7
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
    @DisplayName("空转在非轮转OC → 当前队不被过滤，出现在推荐结果中")
    void idlingInNonRotationOc_shouldIncludeCurrentOc() {
        // Given: 候选列表包含当前队和另一个轮转队
        when(ocDao.queryRecrutList(FACTION_HP)).thenReturn(new java.util.ArrayList<>(List.of(breakBankOc)));

        // 空槽位（当前队的不在empty里，但会通过findEmptySlotList追加）
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new java.util.ArrayList<>(List.of(breakBankSlot)));

        // 用户成功率：满足Break the Bank（触发大锅饭）
        TornFactionOcUserDO passRate = new TornFactionOcUserDO();
        passRate.setOcName(OC_BREAK_BANK);
        passRate.setPosition("Engineer#1");
        passRate.setPassRate(75);
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(passRate));

        // checkIsReassignRecommended → true（用户有轮转OC成功率）
        when(ocRecommendManager.checkIsReassignRecommended(eq(user), anyList())).thenReturn(true);

        // findSlotSetting → 正常返回
        TornSettingOcSlotDO slotSetting = new TornSettingOcSlotDO();
        slotSetting.setSlotShortCode("Engineer#1");
        slotSetting.setPassRate(60);
        slotSetting.setPriority(15);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(slotSetting);

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

        // Then: 两个队都应该出现
        assertThat(result).hasSize(2);
        assertThat(result).extracting(OcRecommendationVO::getOcName)
                .contains(OC_ACE, OC_BREAK_BANK);
    }

    // ========================================================
    // 基线过滤：评分低于当前队的队伍不展示
    // ========================================================

    @Test
    @DisplayName("当前队评分最高 → 只展示当前队自己")
    void currentTeamHasHighestScore_onlyCurrentTeamReturned() {
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

        // 当前队(Ace)评分90，别队(Break Bank)评分85
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    TornFactionOcDO oc = inv.getArgument(1);
                    return oc.getId() == 1L ? BigDecimal.valueOf(90) : BigDecimal.valueOf(85);
                });
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("即将停转");

        // When
        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        // Then: 只有当前队（别队85 < 基线90，被过滤）
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getOcName()).isEqualTo(OC_ACE);
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
