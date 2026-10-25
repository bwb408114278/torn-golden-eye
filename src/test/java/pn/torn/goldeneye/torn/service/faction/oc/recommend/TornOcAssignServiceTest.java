package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcSlotEligibility;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.OcYieldEstimatorRegistry;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcRecommendRanker;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcScarcityEstimator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * OC分配服务测试：覆盖准入放宽、停转队优先与共享OC对象不被改写。
 *
 * @author Bai
 * @version 1.9.4
 * @since 2026.08.20
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC分配服务测试")
class TornOcAssignServiceTest {
    private static final long FACTION_ID = 2095L;
    private static final long USER_ID = 100L;

    @Mock
    private TornOcRecommendManager ocRecommendManager;
    @Mock
    private OcYieldEstimatorRegistry yieldEstimatorRegistry;
    @Mock
    private TornFactionOcDAO ocDao;
    @Mock
    private TornFactionOcSlotDAO ocSlotDao;
    @Mock
    private TornFactionOcUserDAO ocUserDao;
    @Mock
    private TornUserDAO userDao;

    private final OcRecommendRanker ranker = new OcRecommendRanker(new OcScarcityEstimator());

    private TornOcAssignService assignService;
    private TornUserDO user;
    private TornFactionOcUserDO userData;
    private TornSettingOcSlotDO setting;

    @BeforeEach
    void setUp() {
        assignService = new TornOcAssignService(ocRecommendManager, yieldEstimatorRegistry, ranker,
                ocDao, ocSlotDao, ocUserDao, userDao);

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
        setting.setOcName("OC");
        setting.setRank(8);

        lenient().when(ocUserDao.queryFactionMatrix(FACTION_ID)).thenReturn(List.of());
        lenient().when(ocRecommendManager.resolveEligibility(anyLong(), any(), any(), anyList()))
                .thenReturn(new OcSlotEligibility(setting, 80));
        lenient().when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("高成功率");
    }

    @Test
    @DisplayName("大锅饭名单外的OC也能参与批量分配")
    void assign_shouldIncludeOcOutsideReassignRoster() {
        TornFactionOcDO oc = buildOc(1L, LocalDateTime.now().plusDays(1));
        TornFactionOcSlotDO slot = buildSlot(11L, oc.getId());
        stubCandidates(List.of(oc), List.of(slot), Map.of(1L, estimate(300_000_000L, 6)));

        Map<TornUserDO, OcRecommendationVO> result = assignService.assignUserList(
                FACTION_ID, Map.of(user, List.of(userData)));

        assertThat(result.get(user)).isNotNull();
        assertThat(result.get(user).getOcId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("同等产出下停转队优先于新队分配")
    void assign_shouldPreferPausedOc() {
        TornFactionOcDO newTeamOc = buildOc(1L, null);
        TornFactionOcDO pausedOc = buildOc(2L, LocalDateTime.now().minusDays(1));
        TornFactionOcSlotDO newTeamSlot = buildSlot(11L, newTeamOc.getId());
        TornFactionOcSlotDO pausedSlot = buildSlot(21L, pausedOc.getId());
        stubCandidates(List.of(newTeamOc, pausedOc), List.of(newTeamSlot, pausedSlot),
                Map.of(1L, estimate(300_000_000L, 6), 2L, estimate(300_000_000L, 6)));

        Map<TornUserDO, OcRecommendationVO> result = assignService.assignUserList(
                FACTION_ID, Map.of(user, List.of(userData)));

        assertThat(result.get(user).getOcId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("分配过程不改写共享的OC准备时间")
    void assign_shouldNotMutateSharedReadyTime() {
        LocalDateTime readyTime = LocalDateTime.now().plusHours(3);
        TornFactionOcDO oc = buildOc(1L, readyTime);
        TornFactionOcSlotDO slot = buildSlot(11L, oc.getId());
        stubCandidates(List.of(oc), List.of(slot), Map.of(1L, estimate(300_000_000L, 6)));

        assignService.assignUserList(FACTION_ID, Map.of(user, List.of(userData)));

        assertThat(oc.getReadyTime()).isEqualTo(readyTime);
    }

    @Test
    @DisplayName("没有合格岗位时返回空值而不是抛异常")
    void assign_shouldReturnNullValueWhenNoQualifiedSlot() {
        TornFactionOcDO oc = buildOc(1L, LocalDateTime.now().plusDays(1));
        TornFactionOcSlotDO slot = buildSlot(11L, oc.getId());
        when(ocDao.queryRecrutList(FACTION_ID)).thenReturn(List.of(oc));
        when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(List.of(slot));
        lenient().when(yieldEstimatorRegistry.estimateAll(anyCollection(), any()))
                .thenReturn(Map.of(1L, estimate(300_000_000L, 6)));
        when(ocRecommendManager.resolveEligibility(FACTION_ID, oc, slot, List.of(userData))).thenReturn(null);

        Map<TornUserDO, OcRecommendationVO> result = assignService.assignUserList(
                FACTION_ID, Map.of(user, List.of(userData)));

        assertThat(result).containsEntry(user, null);
    }

    @Test
    @DisplayName("相同准备时间的多个OC不会因TreeMap键碰撞丢失")
    void assign_shouldKeepBothOcsWithSameReadyTime() {
        LocalDateTime readyTime = LocalDateTime.now().plusDays(1);
        TornFactionOcDO firstOc = buildOc(1L, readyTime);
        TornFactionOcDO secondOc = buildOc(2L, readyTime);
        TornFactionOcSlotDO firstSlot = buildSlot(11L, firstOc.getId());
        TornFactionOcSlotDO secondSlot = buildSlot(21L, secondOc.getId());
        stubCandidates(List.of(firstOc, secondOc), List.of(firstSlot, secondSlot),
                Map.of(1L, estimate(300_000_000L, 6), 2L, estimate(200_000_000L, 6)));

        Map<TornUserDO, OcRecommendationVO> result = assignService.assignUserList(
                FACTION_ID, Map.of(user, List.of(userData)));

        assertThat(result.get(user)).isNotNull();
        assertThat(result.get(user).getOcId()).isIn(firstOc.getId(), secondOc.getId());
    }

    private void stubCandidates(List<TornFactionOcDO> ocs, List<TornFactionOcSlotDO> slots,
                                Map<Long, OcYieldEstimate> estimates) {
        lenient().when(ocDao.queryRecrutList(FACTION_ID)).thenReturn(new ArrayList<>(ocs));
        lenient().when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(slots);
        lenient().when(yieldEstimatorRegistry.estimateAll(anyCollection(), any())).thenReturn(estimates);
    }

    private OcYieldEstimate estimate(long expectedNetReward, int members) {
        return new OcYieldEstimate(BigDecimal.valueOf(expectedNetReward),
                new BigDecimal("4.5").multiply(BigDecimal.valueOf(members)), OcYieldSourceEnum.MAPPED);
    }

    private TornFactionOcDO buildOc(long id, LocalDateTime readyTime) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setFactionId(FACTION_ID);
        oc.setName("OC" + id);
        oc.setRank(8);
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
