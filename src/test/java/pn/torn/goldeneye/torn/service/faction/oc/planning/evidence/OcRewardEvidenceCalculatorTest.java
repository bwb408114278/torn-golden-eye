package pn.torn.goldeneye.torn.service.faction.oc.planning.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.OcPlanningRewardStatsDO;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OC收益证据计算器测试。聚焦第三层业务先验和完整链聚合，不重复数据库Mapper测试。
 *
 * @author Bai
 * @version 1.9.2
 * @since 2026.08.15
 */
@DisplayName("OC收益证据计算")
class OcRewardEvidenceCalculatorTest {
    private final OcRewardEvidenceCalculator calculator = new OcRewardEvidenceCalculator();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 8, 0);

    @Test
    @DisplayName("金额证据不足时应生成可比较的PRIOR_ONLY业务先验")
    void shouldBuildPriorOnlyEvidenceWithComparablePrior() {
        OcPlanningRewardStatsDO empty = OcPlanningRewardStatsDO.empty(8, "Alpha", 3, 3);

        OcValueEvidence evidence = calculator.buildEvidence(empty, 10, 6, NOW.plusHours(12),
                8, 3, 1);

        assertEquals(OcValueEvidence.Level.PRIOR_ONLY, evidence.level());
        assertNull(evidence.totalValue());
        assertTrue(evidence.usableForAdviceIncrease());
        assertEquals(8, evidence.highestRank());
        assertEquals(3, evidence.totalRequiredMembers());
        assertEquals(1, evidence.chainNodeCount());
    }

    @Test
    @DisplayName("根金额为0但后继形成完整链时链聚合仍生成可比较先验")
    void shouldAggregateChainPriorEvenWhenRootValueIsZero() {
        OcValueEvidence root = new OcValueEvidence(OcValueEvidence.Level.OBSERVED_REWARD,
                BigDecimal.ZERO, 2, NOW.plusHours(6), true, 8, 2, 1);
        OcValueEvidence child = new OcValueEvidence(OcValueEvidence.Level.PRIOR_ONLY,
                null, 3, NOW.plusHours(24), true, 9, 3, 1);

        OcValueEvidence chain = calculator.aggregateChainEvidence(List.of(root, child));

        assertEquals(OcValueEvidence.Level.PRIOR_ONLY, chain.level());
        assertEquals(0, chain.totalValue().compareTo(BigDecimal.ZERO),
                "金额缺失节点应被跳过，链金额保留非空节点的合计");
        assertTrue(chain.usableForAdviceIncrease());
        assertEquals(9, chain.highestRank());
        assertEquals(5, chain.totalRequiredMembers());
        assertEquals(2, chain.chainNodeCount());
    }

    @Test
    @DisplayName("链根节点无直接奖励时应聚合尾端节点金额")
    void shouldAggregateChainValueSkippingNullRootNode() {
        OcValueEvidence root = new OcValueEvidence(OcValueEvidence.Level.PRIOR_ONLY,
                null, 2, NOW.plusHours(6), true, 9, 3, 1);
        OcValueEvidence tail = new OcValueEvidence(OcValueEvidence.Level.REWARD_FLOOR,
                BigDecimal.valueOf(280_005_000L), 3, NOW.plusHours(24), true, 10, 4, 1);

        OcValueEvidence chain = calculator.aggregateChainEvidence(List.of(root, tail));

        assertEquals(OcValueEvidence.Level.PRIOR_ONLY, chain.level());
        assertEquals(0, chain.totalValue().compareTo(BigDecimal.valueOf(280_005_000L)),
                "链金额应为有金额节点的合计，缺失根节点不得掩盖尾端收益");
        assertEquals(7, chain.totalRequiredMembers());
        assertEquals(2, chain.chainNodeCount());
    }

    @Test
    @DisplayName("链内所有节点金额均缺失时链金额仍为空")
    void shouldReturnNullChainValueWhenAllNodesHaveNullValue() {
        OcValueEvidence root = new OcValueEvidence(OcValueEvidence.Level.PRIOR_ONLY,
                null, 2, NOW.plusHours(6), true, 9, 3, 1);
        OcValueEvidence tail = new OcValueEvidence(OcValueEvidence.Level.PRIOR_ONLY,
                null, 3, NOW.plusHours(24), true, 10, 4, 1);

        OcValueEvidence chain = calculator.aggregateChainEvidence(List.of(root, tail));

        assertEquals(OcValueEvidence.Level.PRIOR_ONLY, chain.level());
        assertNull(chain.totalValue(), "全部节点金额缺失时链金额不得伪造成0");
        assertEquals(7, chain.totalRequiredMembers());
        assertEquals(2, chain.chainNodeCount());
    }

    @Test
    @DisplayName("完整链聚合应按最弱节点层级降级")
    void shouldAggregateChainWithWeakestLevel() {
        OcValueEvidence observed = new OcValueEvidence(OcValueEvidence.Level.OBSERVED_REWARD,
                BigDecimal.valueOf(300), 2, NOW.plusHours(6), true, 8, 2, 1);
        OcValueEvidence insufficient = new OcValueEvidence(OcValueEvidence.Level.INSUFFICIENT,
                null, 3, null, false, 0, 0, 0);

        OcValueEvidence chain = calculator.aggregateChainEvidence(List.of(observed, insufficient));

        assertEquals(OcValueEvidence.Level.INSUFFICIENT, chain.level());
        assertFalse(chain.usableForAdviceIncrease());
    }
}
