package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcUserDAO;
import pn.torn.goldeneye.repository.dao.user.TornUserDAO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.torn.manager.faction.crime.recommend.TornOcRecommendManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcRecommendationVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * OC分配服务单元测试，验证停转优先级和相同准备时间下的候选保留。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC分配服务测试")
class TornOcAssignServiceTest {
    private static final long FACTION_ID = 2095L;
    private static final long USER_ID = 100L;

    @Mock
    private TornOcRecommendManager ocRecommendManager;
    @Mock
    private TornFactionOcDAO ocDao;
    @Mock
    private TornFactionOcSlotDAO ocSlotDao;
    @Mock
    private TornFactionOcUserDAO ocUserDao;
    @Mock
    private TornUserDAO userDao;

    @InjectMocks
    private TornOcAssignService assignService;

    private TornUserDO user;
    private TornFactionOcUserDO userData;
    private TornSettingOcSlotDO setting;

    @BeforeEach
    void setUp() {
        user = new TornUserDO();
        user.setId(USER_ID);
        user.setFactionId(FACTION_ID);
        userData = new TornFactionOcUserDO();
        userData.setUserId(USER_ID);
        userData.setFactionId(FACTION_ID);
        userData.setPassRate(80);
        setting = new TornSettingOcSlotDO();
        setting.setPassRate(60);
        setting.setSlotShortCode("Engineer");
        setting.setSlotCode("Engineer#1");
    }

    @Test
    @DisplayName("停转岗位综合评分较低时仍优先分配停转岗位")
    void stoppedOc_shouldHavePriorityOverHigherScoreReadyOc() {
        TornFactionOcDO stoppedOc = buildOc(1L, LocalDateTime.now().minusHours(1));
        TornFactionOcDO readyOc = buildOc(2L, LocalDateTime.now().plusDays(1));
        TornFactionOcSlotDO stoppedSlot = buildSlot(11L, stoppedOc.getId());
        TornFactionOcSlotDO readySlot = buildSlot(12L, readyOc.getId());
        stubCandidates(List.of(stoppedOc, readyOc), List.of(stoppedSlot, readySlot));
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), eq(stoppedOc), any(), any()))
                .thenReturn(BigDecimal.ONE);
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), eq(readyOc), any(), any()))
                .thenReturn(BigDecimal.valueOf(100));

        Map<TornUserDO, OcRecommendationVO> result = assignService.assignUserList(
                FACTION_ID, Map.of(user, List.of(userData)));

        assertThat(result.get(user).getOcId()).isEqualTo(stoppedOc.getId());
    }

    @Test
    @DisplayName("相同准备时间的多个OC不会因TreeMap键碰撞丢失")
    void sameReadyTime_shouldKeepBothOcs() {
        LocalDateTime readyTime = LocalDateTime.now().plusDays(1);
        TornFactionOcDO firstOc = buildOc(1L, readyTime);
        TornFactionOcDO secondOc = buildOc(2L, readyTime);
        TornFactionOcSlotDO firstSlot = buildSlot(11L, firstOc.getId());
        TornFactionOcSlotDO secondSlot = buildSlot(12L, secondOc.getId());
        stubCandidates(List.of(firstOc, secondOc), List.of(firstSlot, secondSlot));
        when(ocRecommendManager.calcRecommendScore(anyBoolean(), any(), any(), any()))
                .thenReturn(BigDecimal.TEN);

        Map<TornUserDO, OcRecommendationVO> result = assignService.assignUserList(
                FACTION_ID, Map.of(user, List.of(userData)));

        assertThat(result.get(user)).isNotNull();
        assertThat(result.get(user).getOcId()).isIn(firstOc.getId(), secondOc.getId());
    }

    @Test
    @DisplayName("没有合格岗位时返回空值而不是抛异常")
    void noQualifiedSlot_shouldReturnNullValue() {
        TornFactionOcDO oc = buildOc(1L, LocalDateTime.now().plusDays(1));
        TornFactionOcSlotDO slot = buildSlot(11L, oc.getId());
        when(ocDao.queryRecrutList(FACTION_ID)).thenReturn(List.of(oc));
        when(ocSlotDao.queryEmptySlotList(List.of(oc))).thenReturn(List.of(slot));
        when(ocRecommendManager.checkIsReassignRecommended(user, List.of(userData))).thenReturn(false);
        when(ocRecommendManager.findSlotSetting(FACTION_ID, oc, slot)).thenReturn(null);

        Map<TornUserDO, OcRecommendationVO> result = assignService.assignUserList(
                FACTION_ID, Map.of(user, List.of(userData)));

        assertThat(result).containsEntry(user, null);
    }

    private void stubCandidates(List<TornFactionOcDO> ocs, List<TornFactionOcSlotDO> slots) {
        when(ocDao.queryRecrutList(FACTION_ID)).thenReturn(new ArrayList<>(ocs));
        when(ocSlotDao.queryEmptySlotList(ocs)).thenReturn(slots);
        when(ocRecommendManager.checkIsReassignRecommended(user, List.of(userData))).thenReturn(false);
        when(ocRecommendManager.findSlotSetting(anyLong(), any(), any())).thenReturn(setting);
        when(ocRecommendManager.findUserPassRate(eq(List.of(userData)), any(), eq(setting))).thenReturn(userData);
        when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("已停转");
    }

    private TornFactionOcDO buildOc(long id, LocalDateTime readyTime) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setFactionId(FACTION_ID);
        oc.setName("OC" + id);
        oc.setRank(5);
        oc.setReadyTime(readyTime);
        return oc;
    }

    private TornFactionOcSlotDO buildSlot(long id, long ocId) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setId(id);
        slot.setOcId(ocId);
        slot.setPosition("Engineer#1");
        return slot;
    }
}
