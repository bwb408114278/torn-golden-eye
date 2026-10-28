package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcSlotDAO;
import pn.torn.goldeneye.repository.model.faction.oc.OcIncomeYieldStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcChainDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcManager;
import pn.torn.goldeneye.torn.manager.setting.TornSettingOcPlanningManager;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldEstimate;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.OcYieldSourceEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * OC产出估算测试：覆盖实测聚合、映射回退、链归约、人天口径与可信度加权。
 *
 * @author Bai
 * @version 1.9.4
 * @since 2026.10.23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OC产出估算测试")
class OcYieldEstimatorTest {
    private static final long FACTION_ID = 2095L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    @Mock
    private TornFactionOcIncomeDAO ocIncomeDao;
    @Mock
    private TornSettingOcManager settingOcManager;
    @Mock
    private TornSettingOcPlanningManager planningManager;
    @Mock
    private TornFactionOcSlotDAO ocSlotDao;

    private MeasuredOcYieldEstimator measuredEstimator;
    private MappedOcYieldEstimator mappedEstimator;
    private OcYieldEstimatorRegistry registry;

    @BeforeEach
    void setUp() {
        measuredEstimator = new MeasuredOcYieldEstimator(ocIncomeDao, settingOcManager);
        mappedEstimator = new MappedOcYieldEstimator(settingOcManager, planningManager);
        registry = new OcYieldEstimatorRegistry(
                List.of(measuredEstimator, mappedEstimator), measuredEstimator, mappedEstimator, ocSlotDao);
        lenient().when(planningManager.getChains()).thenReturn(List.of());
        lenient().when(ocSlotDao.queryListByOcId(anyCollection())).thenReturn(List.of());
    }

    @Test
    @DisplayName("实测来源按收益记录聚合每次尝试的期望净收益")
    void measuredEstimator_shouldAggregateIncomeStats() {
        when(settingOcManager.getList()).thenReturn(List.of(setting("Break the Bank", 8, 6, 240_000_000L)));
        when(ocIncomeDao.queryYieldStats(FACTION_ID)).thenReturn(List.of(stats("Break the Bank", 8, 4, 3, 1_000_000_000L)));

        OcYieldEstimate estimate = measuredEstimator.estimate(oc(1L, "Break the Bank", 8), context());

        assertThat(estimate.source()).isEqualTo(OcYieldSourceEnum.MEASURED);
        assertThat(estimate.expectedNetReward()).isEqualByComparingTo(new BigDecimal("250000000"));
        assertThat(estimate.expectedMemberDays()).isEqualByComparingTo(new BigDecimal("36"));
        assertThat(estimate.netPerMemberDay()).isEqualByComparingTo(new BigDecimal("6944444.44"));
    }

    @Test
    @DisplayName("期望收益缺失时退回等级阶梯映射，且纯道具OC不为0产出")
    void mappedEstimator_shouldFallBackToRankLadderAndKeepItemOnlyReward() {
        when(settingOcManager.getList()).thenReturn(List.of(
                setting("Manifest Cruelty", 8, 4, 0L),
                setting("Ship Happens", 8, 5, 128_100_000L)));

        OcYieldEstimate ladderMapped = mappedEstimator.estimate(oc(1L, "Manifest Cruelty", 8), context());
        OcYieldEstimate itemOnly = mappedEstimator.estimate(oc(2L, "Ship Happens", 8), context());

        assertThat(ladderMapped.source()).isEqualTo(OcYieldSourceEnum.MAPPED);
        assertThat(ladderMapped.expectedNetReward()).isEqualByComparingTo(
                new BigDecimal("191300000").multiply(new BigDecimal("0.754")));
        assertThat(ladderMapped.expectedMemberDays()).isEqualByComparingTo(new BigDecimal("24"));
        assertThat(itemOnly.expectedNetReward()).isEqualByComparingTo(new BigDecimal("128100000"));
        assertThat(itemOnly.expectedNetReward()).isPositive();
        assertThat(itemOnly.netPerMemberDay()).isPositive();
    }

    @Test
    @DisplayName("目录配置缺岗位人数时用人天按该队实际岗位数，而不是按1人")
    void mappedEstimator_shouldFallBackToActualSlotCountWhenConfigMissing() {
        when(settingOcManager.getList()).thenReturn(List.of(setting("Snow Blind", 4, 0, 0L)));
        when(ocIncomeDao.queryYieldStats(FACTION_ID)).thenReturn(List.of());
        when(ocSlotDao.queryListByOcId(anyCollection()))
                .thenReturn(List.of(slot(1L), slot(1L), slot(1L), slot(1L)));

        OcYieldEstimate fromSlots = registry.estimateAll(List.of(oc(1L, "Snow Blind", 4)),
                NOW).get(1L);
        OcYieldEstimate withoutSlots = mappedEstimator.estimate(oc(1L, "Snow Blind", 4), context());

        assertThat(fromSlots.expectedMemberDays()).isEqualByComparingTo(new BigDecimal("24"));
        assertThat(withoutSlots.expectedMemberDays()).isEqualByComparingTo(new BigDecimal("6"));
        assertThat(fromSlots.netPerMemberDay()).isLessThan(withoutSlots.netPerMemberDay());
    }

    @Test
    @DisplayName("链式OC奖励只取叶子、人天累加全部步骤并折前置成功率")
    void mappedEstimator_shouldReduceWholeChain() {
        when(settingOcManager.getList()).thenReturn(List.of(
                setting("Lock Stock", 8, 5, 0L),
                setting("Hostile Takeover", 9, 6, 0L)));
        when(planningManager.getChains()).thenReturn(List.of(
                chainEdge("LOCK_HOSTILE", "Lock Stock", 8, "Hostile Takeover", 9, 1)));

        OcYieldEstimate fromRoot = mappedEstimator.estimate(oc(1L, "Lock Stock", 8), context());
        OcYieldEstimate fromLeaf = mappedEstimator.estimate(oc(2L, "Hostile Takeover", 9), context());
        BigDecimal leafExpected = new BigDecimal("641400000").multiply(new BigDecimal("0.912"));

        assertThat(fromRoot.source()).isEqualTo(OcYieldSourceEnum.CHAIN_MAPPED);
        assertThat(fromRoot.expectedNetReward()).isEqualByComparingTo(
                leafExpected.multiply(new BigDecimal("0.754")));
        assertThat(fromRoot.expectedMemberDays()).isEqualByComparingTo(new BigDecimal("66"));
        assertThat(fromLeaf.expectedNetReward()).isEqualByComparingTo(fromRoot.expectedNetReward());
        assertThat(fromLeaf.expectedMemberDays()).isEqualByComparingTo(fromRoot.expectedMemberDays());
    }

    @Test
    @DisplayName("多步链的叶子奖励按全部前置成功率折减，前置失败即整链减记")
    void mappedEstimator_shouldDiscountLeafRewardByAllPrefixRates() {
        when(settingOcManager.getList()).thenReturn(List.of(
                setting("Manifest Cruelty", 8, 4, 0L),
                setting("Gone Fission", 9, 5, 0L),
                setting("Crane Reaction", 10, 4, 0L)));
        when(planningManager.getChains()).thenReturn(List.of(
                chainEdge("MANIFEST_CRANE", "Manifest Cruelty", 8, "Gone Fission", 9, 1),
                chainEdge("MANIFEST_CRANE", "Gone Fission", 9, "Crane Reaction", 10, 2)));

        OcYieldEstimate wholeChain = mappedEstimator.estimate(oc(1L, "Manifest Cruelty", 8), context());
        // 叶子r10自身的期望奖励 = 1B × 100%；前置两步成功率 0.754 × 0.912 = 0.687648
        BigDecimal leafExpected = new BigDecimal("1000000000");
        BigDecimal prefixRate = new BigDecimal("0.754").multiply(new BigDecimal("0.912"));

        assertThat(wholeChain.source()).isEqualTo(OcYieldSourceEnum.CHAIN_MAPPED);
        assertThat(wholeChain.expectedNetReward())
                .isEqualByComparingTo(leafExpected.multiply(prefixRate));
        assertThat(wholeChain.expectedNetReward()).isLessThan(leafExpected);
        // 前置步骤不付钱但真实占用人力：人天累加全部三步
        assertThat(wholeChain.expectedMemberDays()).isEqualByComparingTo(new BigDecimal("78"));
        assertThat(wholeChain.netPerMemberDay()).isEqualByComparingTo(
                new BigDecimal("687648000").divide(new BigDecimal("78"), 2, RoundingMode.HALF_UP));
    }

    @Test
    @DisplayName("实测与等级映射按样本量加权：样本越少越向映射值收缩")
    void registry_shouldShrinkMeasuredValueBySampleCount() {
        TornFactionOcDO oc = oc(1L, "Break the Bank", 8);
        when(settingOcManager.getList()).thenReturn(List.of(setting("Break the Bank", 8, 6, 240_000_000L)));
        when(ocIncomeDao.queryYieldStats(FACTION_ID)).thenReturn(List.of(stats("Break the Bank", 8, 2, 2, 400_000_000L)));

        OcYieldEstimate shrunk = registry.estimateAll(List.of(oc), NOW).get(1L);

        // 实测每次200M，权重2；映射240M，权重10 → (2×200 + 10×240) ÷ 12 = 233.33M
        assertThat(shrunk.source()).isEqualTo(OcYieldSourceEnum.MEASURED);
        assertThat(shrunk.expectedNetReward()).isEqualByComparingTo(new BigDecimal("233333333.33"));
        assertThat(shrunk.expectedMemberDays()).isEqualByComparingTo(new BigDecimal("36"));
    }

    @Test
    @DisplayName("批量估算复用同一次实测统计与岗位编制")
    void registry_shouldEstimateAllInBatch() {
        TornFactionOcDO first = oc(1L, "Break the Bank", 8);
        TornFactionOcDO second = oc(2L, "Clinical Precision", 8);
        when(settingOcManager.getList()).thenReturn(List.of(
                setting("Break the Bank", 8, 6, 240_000_000L),
                setting("Clinical Precision", 8, 4, 80_000_000L)));
        when(ocIncomeDao.queryYieldStats(FACTION_ID)).thenReturn(List.of(
                stats("Break the Bank", 8, 5, 5, 1_500_000_000L),
                stats("Clinical Precision", 8, 5, 5, 300_000_000L)));

        Map<Long, OcYieldEstimate> estimates = registry.estimateAll(List.of(first, second), NOW);

        assertThat(estimates).hasSize(2);
        assertThat(estimates.get(1L).source()).isEqualTo(OcYieldSourceEnum.MEASURED);
        // (5×300 + 10×240) ÷ 15 = 260M；(5×60 + 10×80) ÷ 15 = 73.33M
        assertThat(estimates.get(1L).expectedNetReward()).isEqualByComparingTo(new BigDecimal("260000000"));
        assertThat(estimates.get(2L).expectedNetReward()).isEqualByComparingTo(new BigDecimal("73333333.33"));
    }

    private OcYieldContext context() {
        return new OcYieldContext(NOW, Map.of());
    }

    private TornFactionOcDO oc(long id, String name, int rank) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setFactionId(FACTION_ID);
        oc.setName(name);
        oc.setRank(rank);
        return oc;
    }

    private TornFactionOcSlotDO slot(long ocId) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(ocId);
        return slot;
    }

    private TornSettingOcDO setting(String ocName, int rank, int members, Long expectedReward) {
        TornSettingOcDO setting = new TornSettingOcDO();
        setting.setOcName(ocName);
        setting.setRank(rank);
        setting.setRequiredMembers(members);
        setting.setExpectedReward(expectedReward);
        return setting;
    }

    private OcIncomeYieldStatsDO stats(String ocName, int rank, int attempts, int successes, long netRewardSum) {
        OcIncomeYieldStatsDO stats = new OcIncomeYieldStatsDO();
        stats.setFactionId(FACTION_ID);
        stats.setOcName(ocName);
        stats.setRank(rank);
        stats.setAttemptCount(attempts);
        stats.setSuccessCount(successes);
        stats.setNetRewardSum(netRewardSum);
        return stats;
    }

    private TornSettingOcChainDO chainEdge(String chainCode, String parentName, int parentRank,
                                           String childName, int childRank, int sequenceNo) {
        TornSettingOcChainDO edge = new TornSettingOcChainDO();
        edge.setChainCode(chainCode);
        edge.setParentOcName(parentName);
        edge.setParentRank(parentRank);
        edge.setChildOcName(childName);
        edge.setChildRank(childRank);
        edge.setSequenceNo(sequenceNo);
        edge.setEnabled(true);
        return edge;
    }
}
