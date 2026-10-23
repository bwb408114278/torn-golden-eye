package pn.torn.goldeneye.torn.service.faction.oc.recommend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import pn.torn.goldeneye.torn.model.faction.crime.recommend.*;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.OcYieldEstimatorRegistry;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcOpportunityCostRanker;
import pn.torn.goldeneye.torn.service.faction.oc.recommend.rank.OcScarcityEstimator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OC推荐服务测试：覆盖准入放宽、矩阵缺失兜底、新队空准备时间与机会成本基线过滤。
 *
 * @author Bai
 * @version 1.9.3
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
    private OcYieldEstimatorRegistry yieldEstimatorRegistry;
    @Mock
    private TornFactionOcDAO ocDao;
    @Mock
    private TornFactionOcSlotDAO ocSlotDao;
    @Mock
    private TornFactionOcUserDAO ocUserDao;

    private final OcOpportunityCostRanker ranker = new OcOpportunityCostRanker(new OcScarcityEstimator());

    private TornOcRecommendService recommendService;
    private TornUserDO user;
    private TornFactionOcDO aceOc;
    private TornFactionOcDO breakBankOc;
    private TornFactionOcSlotDO aceSlot;
    private TornFactionOcSlotDO breakBankSlot;
    private TornSettingOcSlotDO slotSetting;
    private TornFactionOcUserDO matched;
    private OcSlotDictBO joinedOc;

    @BeforeEach
    void setUp() {
        recommendService = new TornOcRecommendService(ocRecommendManager, yieldEstimatorRegistry,
                ranker, ocDao, ocSlotDao, ocUserDao);

        user = new TornUserDO();
        user.setId(USER_ID);
        user.setFactionId(FACTION_HP);

        aceOc = buildOc(1L, OC_ACE, 9, LocalDateTime.now().plusHours(3));
        aceSlot = new TornFactionOcSlotDO();
        aceSlot.setId(10L);
        aceSlot.setOcId(1L);
        aceSlot.setUserId(USER_ID);
        aceSlot.setPosition("Engineer#1");
        aceSlot.setProgress(BigDecimal.ZERO);
        joinedOc = new OcSlotDictBO(aceOc, aceSlot);

        breakBankOc = buildOc(2L, OC_BREAK_BANK, 8, LocalDateTime.now().plusHours(6));
        breakBankSlot = buildEmptySlot(21L, 2L, "Engineer#1");

        slotSetting = buildSetting(OC_BREAK_BANK, 8, "Engineer#1", 60);
        matched = new TornFactionOcUserDO();
        matched.setUserId(USER_ID);
        matched.setPassRate(80);
    }

    @Test
    @DisplayName("大锅饭名单外的OC也能进入推荐结果")
    void recommend_shouldIncludeOcOutsideReassignRoster() {
        stubQuery(List.of(breakBankOc), List.of(breakBankSlot));
        stubEstimates(Map.of(2L, estimate(300_000_000L, 6)));

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getOcName()).isEqualTo(OC_BREAK_BANK);
        assertThat(result.getFirst().getOpportunityCost()).isNotNull();
        assertThat(result.getFirst().getRecommendScore()).isEqualByComparingTo(
                result.getFirst().getOpportunityCost());
    }

    @Test
    @DisplayName("成功率矩阵为空时仍返回候选而不是空结果")
    void recommend_shouldNotReturnEmptyWhenMatrixMissing() {
        stubQuery(List.of(breakBankOc), List.of(breakBankSlot));
        stubEstimates(Map.of(2L, estimate(300_000_000L, 6)));
        when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of());
        lenient().when(ocRecommendManager.resolveEligibility(anyLong(), any(), any(), anyList()))
                .thenReturn(new OcSlotEligibility(slotSetting, null));

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getOcName()).isEqualTo(OC_BREAK_BANK);
    }

    @Test
    @DisplayName("新队准备时间为空时正常返回且按新队展示理由")
    void recommend_shouldHandleNewTeamWithoutReadyTime() {
        breakBankOc.setReadyTime(null);
        stubQuery(List.of(breakBankOc), List.of(breakBankSlot));
        stubEstimates(Map.of(2L, estimate(300_000_000L, 6)));

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getReadyTime()).isNull();
        verify(ocRecommendManager).buildRecommendReason(isNull(), anyInt());
    }

    @Test
    @DisplayName("停转队因被锁死人天排在同等产出的新队之前")
    void recommend_shouldRankOcWithLockedMembersFirst() {
        TornFactionOcDO newTeamOc = buildOc(2L, OC_BREAK_BANK, 8, null);
        TornFactionOcDO pausedOc = buildOc(3L, "Blast from the Past", 7, LocalDateTime.now().minusDays(1));
        TornFactionOcSlotDO newTeamSlot = buildEmptySlot(21L, 2L, "Engineer#1");
        TornFactionOcSlotDO pausedSlot = buildEmptySlot(31L, 3L, "Engineer#1");
        TornFactionOcSlotDO lockedSlot = new TornFactionOcSlotDO();
        lockedSlot.setId(32L);
        lockedSlot.setOcId(3L);
        lockedSlot.setUserId(999L);
        lockedSlot.setProgress(BigDecimal.ONE);
        lockedSlot.setJoinTime(LocalDateTime.now().minusDays(2));

        stubQuery(List.of(newTeamOc, pausedOc), List.of(newTeamSlot, pausedSlot));
        stubEstimates(Map.of(2L, estimate(300_000_000L, 6), 3L, estimate(300_000_000L, 6)));
        when(ocSlotDao.queryListByOc(anyList())).thenReturn(List.of(lockedSlot));

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, null);

        assertThat(result).extracting(OcRecommendationVO::getOcId).containsExactly(3L, 2L);
    }

    @Test
    @DisplayName("同一OC的多个空位各自成行，不做OC级去重")
    void recommend_shouldKeepMultipleSlotsOfSameOc() {
        TornFactionOcSlotDO secondSlot = buildEmptySlot(22L, 2L, "Engineer#2");
        stubQuery(List.of(breakBankOc), List.of(breakBankSlot, secondSlot));
        stubEstimates(Map.of(2L, estimate(300_000_000L, 6)));

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, null);

        assertThat(result).extracting(OcRecommendationVO::getOcId).containsExactly(2L, 2L);
        assertThat(result).extracting(OcRecommendationVO::getRecommendedPosition)
                .containsExactly("Engineer#1", "Engineer#2");
    }

    @Test
    @DisplayName("低于当前岗位机会成本的候选被过滤")
    void recommend_shouldFilterCandidatesBelowCurrentBaseline() {
        TornFactionOcSlotDO aceEmptySlot = buildEmptySlot(11L, 1L, "Engineer#2");
        stubQuery(List.of(aceOc, breakBankOc), List.of(aceEmptySlot, breakBankSlot));
        stubEstimates(Map.of(1L, estimate(500_000_000L, 5), 2L, estimate(100_000_000L, 6)));

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("当前OC被禁用时不使用其机会成本作为基线")
    void recommend_shouldIgnoreBaselineWhenCurrentOcDisabled() {
        aceSlot.setProgress(BigDecimal.ONE);
        stubQuery(List.of(aceOc, breakBankOc), List.of(buildEmptySlot(11L, 1L, "Engineer#2"), breakBankSlot));
        stubEstimates(Map.of(1L, estimate(500_000_000L, 5), 2L, estimate(100_000_000L, 6)));
        when(ocRecommendManager.isOcDisabled(FACTION_HP, aceOc)).thenReturn(true);
        when(ocRecommendManager.resolveEligibility(anyLong(), eq(aceOc), any(), anyList())).thenReturn(null);

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        assertThat(result).extracting(OcRecommendationVO::getOcId).contains(2L);
    }

    @Test
    @DisplayName("已有进度的成员只返回当前OC内的可换岗位")
    void recommend_shouldOnlyReturnCurrentOcWhenProgressExists() {
        aceSlot.setProgress(BigDecimal.ONE);
        TornFactionOcSlotDO aceEmptySlot = buildEmptySlot(11L, 1L, "Engineer#2");
        stubQuery(List.of(aceOc, breakBankOc), List.of(aceEmptySlot, breakBankSlot));
        stubEstimates(Map.of(1L, estimate(300_000_000L, 5), 2L, estimate(300_000_000L, 6)));
        stubSlotRequirementByPosition(OC_ACE, 9);

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        assertThat(result).isNotEmpty();
        assertThat(result).extracting(OcRecommendationVO::getOcId).containsOnly(1L);
    }

    @Test
    @DisplayName("成功率低于岗位要求时不进入候选")
    void recommend_shouldSkipSlotBelowPassRateRequirement() {
        stubQuery(List.of(breakBankOc), List.of(breakBankSlot));
        stubEstimates(Map.of(2L, estimate(300_000_000L, 6)));
        lenient().when(ocRecommendManager.resolveEligibility(anyLong(), any(), any(), anyList()))
                .thenReturn(new OcSlotEligibility(slotSetting, 50));

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("当前OC不在推荐范围时基线仍复用同一次批量估算")
    void recommend_shouldReuseBatchEstimateForBaselineOfJoinedOc() {
        aceSlot.setProgress(BigDecimal.ONE);
        stubQuery(List.of(breakBankOc), List.of(breakBankSlot));
        stubEstimates(Map.of(1L, estimate(500_000_000L, 5), 2L, estimate(100_000_000L, 6)));
        when(ocRecommendManager.isOcDisabled(FACTION_HP, aceOc)).thenReturn(true);
        when(ocRecommendManager.resolveEligibility(anyLong(), eq(aceOc), any(), anyList()))
                .thenReturn(new OcSlotEligibility(buildSetting(OC_ACE, 9, "Engineer#1", 60), 80));

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        // 基线若退化为0产出，候选会通过过滤；结果为空即证明基线取到了批量估算里的真实值
        assertThat(result).isEmpty();
        verify(yieldEstimatorRegistry).estimateAll(
                argThat(pool -> pool.stream().anyMatch(oc -> Long.valueOf(1L).equals(oc.getId()))),
                any());
    }

    @Test
    @DisplayName("当前OC的准备时间按减一天归一化且不改动共享对象")
    void recommend_shouldNormalizeJoinedReadyTimeWithoutMutation() {
        LocalDateTime originalReadyTime = aceOc.getReadyTime();
        TornFactionOcSlotDO aceEmptySlot = buildEmptySlot(11L, 1L, "Engineer#2");
        stubQuery(List.of(aceOc), List.of(aceEmptySlot));
        stubEstimates(Map.of(1L, estimate(300_000_000L, 5)));
        stubSlotRequirementByPosition(OC_ACE, 9);

        List<OcRecommendationVO> result = recommendService.recommendOcForUser(user, 3, joinedOc);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getReadyTime()).isEqualTo(originalReadyTime.minusDays(1));
        assertThat(aceOc.getReadyTime()).isEqualTo(originalReadyTime);
    }

    private void stubQuery(List<TornFactionOcDO> recruitOcList, List<TornFactionOcSlotDO> emptySlotList) {
        lenient().when(ocDao.queryRecrutList(FACTION_HP)).thenReturn(new ArrayList<>(recruitOcList));
        lenient().when(ocSlotDao.queryEmptySlotList(anyList())).thenReturn(new ArrayList<>(emptySlotList));
        lenient().when(ocSlotDao.queryListByOc(anyList())).thenReturn(List.of());
        lenient().when(ocUserDao.queryByUserId(USER_ID)).thenReturn(List.of(matched));
        lenient().when(ocUserDao.queryFactionMatrix(FACTION_HP)).thenReturn(List.of());
        lenient().when(ocRecommendManager.resolveEligibility(anyLong(), any(), any(), anyList()))
                .thenReturn(new OcSlotEligibility(slotSetting, 80));
        lenient().when(ocRecommendManager.isOcDisabled(anyLong(), any())).thenReturn(false);
        lenient().when(ocRecommendManager.buildRecommendReason(any(), anyInt())).thenReturn("高成功率");
    }

    /**
     * 让当前岗位可被他人替代、候选岗位无样本，构造「候选严格优于基线」的场景。
     */
    private void stubSlotRequirementByPosition(String ocName, int rank) {
        when(ocRecommendManager.resolveEligibility(anyLong(), any(), any(), anyList())).thenAnswer(invocation ->
                new OcSlotEligibility(buildSetting(ocName, rank,
                        ((TornFactionOcSlotDO) invocation.getArgument(2)).getPosition(), 60), 80));
        when(ocUserDao.queryFactionMatrix(FACTION_HP)).thenReturn(List.of(
                matrixRow(ocName, rank, "Engineer#1", 90, 1L),
                matrixRow(ocName, rank, "Engineer#1", 88, 2L),
                matrixRow(ocName, rank, "Engineer#1", 86, 3L)));
    }

    private TornFactionOcUserDO matrixRow(String ocName, int rank, String position, int passRate, long userId) {
        TornFactionOcUserDO row = new TornFactionOcUserDO();
        row.setFactionId(FACTION_HP);
        row.setUserId(userId);
        row.setOcName(ocName);
        row.setRank(rank);
        row.setPosition(position);
        row.setPassRate(passRate);
        return row;
    }

    private void stubEstimates(Map<Long, OcYieldEstimate> estimates) {
        lenient().when(yieldEstimatorRegistry.estimateAll(anyCollection(), any())).thenReturn(estimates);
    }

    private OcYieldEstimate estimate(long expectedNetReward, int members) {
        return new OcYieldEstimate(BigDecimal.valueOf(expectedNetReward),
                new BigDecimal("4.5").multiply(BigDecimal.valueOf(members)), OcYieldSourceEnum.MAPPED);
    }

    private TornFactionOcDO buildOc(long id, String name, int rank, LocalDateTime readyTime) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setFactionId(FACTION_HP);
        oc.setName(name);
        oc.setRank(rank);
        oc.setReadyTime(readyTime);
        return oc;
    }

    private TornFactionOcSlotDO buildEmptySlot(long slotId, long ocId, String position) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setId(slotId);
        slot.setOcId(ocId);
        slot.setUserId(null);
        slot.setPosition(position);
        return slot;
    }

    private TornSettingOcSlotDO buildSetting(String ocName, int rank, String position, int passRate) {
        TornSettingOcSlotDO setting = new TornSettingOcSlotDO();
        setting.setOcName(ocName);
        setting.setRank(rank);
        setting.setSlotCode(position);
        setting.setSlotShortCode(position);
        setting.setPassRate(passRate);
        setting.setPriority(15);
        return setting;
    }
}
