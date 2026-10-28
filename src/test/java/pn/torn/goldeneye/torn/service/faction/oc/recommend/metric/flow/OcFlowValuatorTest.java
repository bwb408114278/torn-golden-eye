package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DAG分结局估值与固定替补标尺测试。
 *
 * @author Bai
 * @version 1.9.5
 * @since 2026.10.27
 */
@DisplayName("DAG分结局估值与固定标尺测试")
class OcFlowValuatorTest {
    private final OcFlowValuator valuator = new OcFlowValuator(new OcFlowEvaluator());

    @Test
    @DisplayName("两个好结局按各自概率与奖励加权且岗位名归一化")
    void evaluate_shouldWeightEachGoodEnding() {
        OcFlowGraph graph = graph(100_000_000L, 50_000_000L);

        Optional<OcFlowValuation> result = valuator.evaluate(graph,
                Map.of(OcFlowSlotCodes.normalize("Cat Burglar#1"), 0.5), null);

        assertThat(result).isPresent();
        assertThat(result.get().successRate()).isEqualTo(1.0);
        assertThat(result.get().expectedValue()).isEqualByComparingTo(new BigDecimal("75000000"));
    }

    @Test
    @DisplayName("前置无自有奖励时按成功概率乘后置链值")
    void evaluate_shouldUseChildChainValue() {
        OcFlowGraph graph = chainGraph();

        Optional<OcFlowValuation> result = valuator.evaluate(graph,
                Map.of(OcFlowSlotCodes.normalize("Cat Burglar#1"), 0.5), new BigDecimal("200000000"));

        assertThat(result).isPresent();
        assertThat(result.get().expectedValue()).isEqualByComparingTo(new BigDecimal("100000000"));
    }

    @Test
    @DisplayName("独立OC缺结局奖励时不可用而不是零")
    void evaluate_shouldRejectIncompleteRewards() {
        OcFlowGraph graph = graph(100_000_000L, null);

        Optional<OcFlowValuation> result = valuator.evaluate(graph,
                Map.of(OcFlowSlotCodes.normalize("Cat Burglar#1"), 0.5), null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("标尺按等级与核心大众划分并保留85上限")
    void substitute_shouldFollowFixedPolicy() {
        assertThat(OcFlowSubstitutePolicy.substituteRate(requirement(7, 65))).isEqualTo(0.70);
        assertThat(OcFlowSubstitutePolicy.substituteRate(requirement(8, 55))).isEqualTo(0.65);
        assertThat(OcFlowSubstitutePolicy.substituteRate(requirement(8, 60))).isEqualTo(0.70);
        assertThat(OcFlowSubstitutePolicy.substituteRate(requirement(9, 65))).isEqualTo(0.65);
        assertThat(OcFlowSubstitutePolicy.substituteRate(requirement(8, 82))).isEqualTo(0.85);
        assertThat(OcFlowSubstitutePolicy.substituteRate(requirement(10, 65))).isNull();
    }

    private OcFlowGraph graph(Long firstReward, Long secondReward) {
        return OcFlowGraph.of("Test", 8,
                List.of(new OcFlowNode("S1", OcFlowNodeTypeEnum.CHECK, List.of("CatBurglar#1")),
                        new OcFlowNode("G1", OcFlowNodeTypeEnum.GOOD_END, null, firstReward),
                        new OcFlowNode("G2", OcFlowNodeTypeEnum.GOOD_END, null, secondReward)),
                List.of(new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "G1"),
                        new OcFlowEdge("S1", OcFlowBranchEnum.FAIL, "G2")));
    }

    private OcFlowGraph chainGraph() {
        return OcFlowGraph.of("Test chain", 8,
                List.of(new OcFlowNode("S1", OcFlowNodeTypeEnum.CHECK, List.of("CatBurglar#1")),
                        new OcFlowNode("G1", OcFlowNodeTypeEnum.GOOD_END, null),
                        new OcFlowNode("B1", OcFlowNodeTypeEnum.BAD_END, null, 0L)),
                List.of(new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "G1"),
                        new OcFlowEdge("S1", OcFlowBranchEnum.FAIL, "B1")));
    }

    private TornSettingOcSlotDO requirement(int rank, int threshold) {
        TornSettingOcSlotDO requirement = new TornSettingOcSlotDO();
        requirement.setRank(rank);
        requirement.setPassRate(threshold);
        return requirement;
    }
}
