package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.dao.faction.oc.TornFactionOcIncomeDAO;
import pn.torn.goldeneye.repository.model.faction.oc.OcIncomeYieldStatsDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * OC产出估算测试：覆盖实测聚合、映射回退、纯道具奖励与链归约。
 *
 * @author Bai
 * @version 1.9.3
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

    private MeasuredOcYieldEstimator measuredEstimator;
    private MappedOcYieldEstimator mappedEstimator;
    private OcYieldEstimatorRegistry registry;

    @BeforeEach
    void setUp() {
        measuredEstimator = new MeasuredOcYieldEstimator(ocIncomeDao, settingOcManager);
        mappedEstimator = new MappedOcYieldEstimator(settingOcManager, planningManager);
        registry = new OcYieldEstimatorRegistry(
                List.of(measuredEstimator, mappedEstimator), measuredEstimator, mappedEstimator);
        lenient().when(planningManager.getChains()).thenReturn(List.of());
    }

    @Test
    @DisplayName("实测来源按收益记录聚合每次尝试的期望净收益")
    void measuredEstimator_shouldAggregateIncomeStats() {
        when(settingOcManager.getList()).thenReturn(List.of(setting("Break the Bank", 8, 6, 240_000_000L)));
        when(ocIncomeDao.queryYieldStats(FACTION_ID)).thenReturn(List.of(stats("Break the Bank", 8, 4, 3, 1_000_000_000L)));

        OcYieldEstimate estimate = measuredEstimator.estimate(oc(1L, "Break the Bank", 8), NOW);

        assertThat(estimate.source()).isEqualTo(OcYieldSourceEnum.MEASURED);
        assertThat(estimate.expectedNetReward()).isEqualByComparingTo(new BigDecimal("250000000"));
        assertThat(estimate.expectedMemberDays()).isEqualByComparingTo(new BigDecimal("27"));
        assertThat(estimate.netPerMemberDay()).isEqualByComparingTo(new BigDecimal("9259259.26"));
    }

    @Test
    @DisplayName("期望收益缺失时退回等级阶梯映射，且纯道具OC不为0产出")
    void mappedEstimator_shouldFallBackToRankLadderAndKeepItemOnlyReward() {
        when(settingOcManager.getList()).thenReturn(List.of(
                setting("Manifest Cruelty", 8, 4, 0L),
                setting("Ship Happens", 8, 5, 128_100_000L)));

        OcYieldEstimate ladderMapped = mappedEstimator.estimate(oc(1L, "Manifest Cruelty", 8), NOW);
        OcYieldEstimate itemOnly = mappedEstimator.estimate(oc(2L, "Ship Happens", 8), NOW);

        assertThat(ladderMapped.source()).isEqualTo(OcYieldSourceEnum.MAPPED);
        assertThat(ladderMapped.expectedNetReward()).isEqualByComparingTo(
                new BigDecimal("191300000").multiply(new BigDecimal("0.754")));
        assertThat(ladderMapped.expectedMemberDays()).isEqualByComparingTo(new BigDecimal("18"));
        assertThat(itemOnly.expectedNetReward()).isEqualByComparingTo(new BigDecimal("128100000"));
        assertThat(itemOnly.expectedNetReward()).isPositive();
        assertThat(itemOnly.netPerMemberDay()).isPositive();
    }

    @Test
    @DisplayName("链式OC奖励只取叶子、人天累加全部步骤并折前置成功率")
    void mappedEstimator_shouldReduceWholeChain() {
        when(settingOcManager.getList()).thenReturn(List.of(
                setting("Lock Stock", 8, 5, 0L),
                setting("Hostile Takeover", 9, 6, 0L)));
        when(planningManager.getChains()).thenReturn(List.of(
                chainEdge("LOCK_HOSTILE", "Lock Stock", 8, "Hostile Takeover", 9, 1)));

        OcYieldEstimate fromRoot = mappedEstimator.estimate(oc(1L, "Lock Stock", 8), NOW);
        OcYieldEstimate fromLeaf = mappedEstimator.estimate(oc(2L, "Hostile Takeover", 9), NOW);
        BigDecimal leafExpected = new BigDecimal("641400000").multiply(new BigDecimal("0.912"));

        assertThat(fromRoot.source()).isEqualTo(OcYieldSourceEnum.CHAIN_MAPPED);
        assertThat(fromRoot.expectedNetReward()).isEqualByComparingTo(
                leafExpected.multiply(new BigDecimal("0.754")));
        assertThat(fromRoot.expectedMemberDays()).isEqualByComparingTo(new BigDecimal("49.5"));
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

        OcYieldEstimate wholeChain = mappedEstimator.estimate(oc(1L, "Manifest Cruelty", 8), NOW);
        // 叶子r10自身的期望奖励 = 1B × 100%；前置两步成功率 0.754 × 0.912 = 0.687648
        BigDecimal leafExpected = new BigDecimal("1000000000");
        BigDecimal prefixRate = new BigDecimal("0.754").multiply(new BigDecimal("0.912"));

        assertThat(wholeChain.source()).isEqualTo(OcYieldSourceEnum.CHAIN_MAPPED);
        assertThat(wholeChain.expectedNetReward())
                .isEqualByComparingTo(leafExpected.multiply(prefixRate));
        assertThat(wholeChain.expectedNetReward()).isLessThan(leafExpected);
        // 前置步骤不付钱但真实占用人力：人天累加全部三步
        assertThat(wholeChain.expectedMemberDays()).isEqualByComparingTo(new BigDecimal("58.5"));
        assertThat(wholeChain.netPerMemberDay()).isEqualByComparingTo(
                new BigDecimal("687648000").divide(new BigDecimal("58.5"), 2, RoundingMode.HALF_UP));
    }

    @Test
    @DisplayName("注册表按成功样本数切换实测与映射来源")
    void registry_shouldSwitchSourceBySampleCount() {
        TornFactionOcDO oc = oc(1L, "Break the Bank", 8);
        when(settingOcManager.getList()).thenReturn(List.of(setting("Break the Bank", 8, 6, 240_000_000L)));
        when(ocIncomeDao.queryYieldStats(FACTION_ID)).thenReturn(List.of(stats("Break the Bank", 8, 2, 2, 400_000_000L)));

        OcYieldEstimate insufficientSamples = registry.estimateAll(List.of(oc), NOW).get(1L);

        assertThat(insufficientSamples.source()).isEqualTo(OcYieldSourceEnum.MAPPED);
        assertThat(insufficientSamples.expectedNetReward())
                .isEqualByComparingTo(new BigDecimal("240000000"));
    }

    @Test
    @DisplayName("批量估算复用同一次实测统计")
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
        assertThat(estimates.get(1L).expectedNetReward()).isEqualByComparingTo(new BigDecimal("300000000"));
        assertThat(estimates.get(2L).expectedNetReward()).isEqualByComparingTo(new BigDecimal("60000000"));
    }

    private TornFactionOcDO oc(long id, String name, int rank) {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(id);
        oc.setFactionId(FACTION_ID);
        oc.setName(name);
        oc.setRank(rank);
        return oc;
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
