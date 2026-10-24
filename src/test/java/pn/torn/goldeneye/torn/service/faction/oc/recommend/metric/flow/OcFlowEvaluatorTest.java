package pn.torn.goldeneye.torn.service.faction.oc.recommend.metric.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.OcFlowBranchEnum;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.OcFlowEdge;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.OcFlowGraph;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.OcFlowNode;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.OcFlowNodeTypeEnum;
import pn.torn.goldeneye.torn.model.faction.crime.recommend.flow.OcFlowOutcome;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * OC流程图求值测试：合成图覆盖分流、汇流与缺省成功率，真实图用于回归上游计算器口径。
 *
 * <p>真实图数据取自 Break the Bank（文档表逐位校验版），探针值为与上游 /api/CalculateSuccess
 * 逐组比对后自建实现的结果，因此本测试同时保护「求值语义」与「配置数据」两侧不被改坏。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
@DisplayName("OC流程图求值测试")
class OcFlowEvaluatorTest {
    private final OcFlowEvaluator evaluator = new OcFlowEvaluator();

    @Test
    @DisplayName("单判定按成功率分流到好结局与坏结局")
    void evaluate_shouldSplitByCheckPassRate() {
        OcFlowGraph graph = singleCheckGraph(new OcFlowNode("S1", OcFlowNodeTypeEnum.CHECK, List.of("Muscle#1")));

        OcFlowOutcome outcome = evaluator.evaluate(graph, Map.of("Muscle#1", 0.7), 0.5);

        assertThat(outcome.successRate()).isCloseTo(0.7, within(1e-12));
        assertThat(outcome.endingRate("G1")).isCloseTo(0.7, within(1e-12));
        assertThat(outcome.endingRate("B1")).isCloseTo(0.3, within(1e-12));
    }

    @Test
    @DisplayName("多参与者判定取参与者成功率的算术平均")
    void evaluate_shouldAverageFlexParticipants() {
        OcFlowGraph graph = singleCheckGraph(
                new OcFlowNode("S1", OcFlowNodeTypeEnum.CHECK, List.of("Muscle#1", "Thief#1")));

        OcFlowOutcome outcome = evaluator.evaluate(graph, Map.of("Muscle#1", 0.6, "Thief#1", 0.8), 0.5);

        assertThat(outcome.successRate()).isCloseTo(0.7, within(1e-12));
    }

    @Test
    @DisplayName("两条分支汇入同一判定时概率质量合并后继续推进")
    void evaluate_shouldMergeMassAtConvergedCheck() {
        OcFlowGraph graph = OcFlowGraph.of("Test", 8,
                List.of(new OcFlowNode("S1", OcFlowNodeTypeEnum.CHECK, List.of("Muscle#1")),
                        new OcFlowNode("S2", OcFlowNodeTypeEnum.CHECK, List.of("Muscle#1")),
                        new OcFlowNode("G1", OcFlowNodeTypeEnum.GOOD_END, null),
                        new OcFlowNode("B1", OcFlowNodeTypeEnum.BAD_END, null)),
                List.of(new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "S2"),
                        new OcFlowEdge("S1", OcFlowBranchEnum.FAIL, "S2"),
                        new OcFlowEdge("S2", OcFlowBranchEnum.PASS, "G1"),
                        new OcFlowEdge("S2", OcFlowBranchEnum.FAIL, "B1")));

        OcFlowOutcome outcome = evaluator.evaluate(graph, Map.of("Muscle#1", 0.5), 0.0);

        assertThat(outcome.successRate()).isCloseTo(0.5, within(1e-12));
    }

    @Test
    @DisplayName("末判定失败落到好结局时该分支仍计入成功")
    void evaluate_shouldCountGoodEndReachedByFailBranch() {
        OcFlowGraph graph = OcFlowGraph.of("Test", 8,
                List.of(new OcFlowNode("S1", OcFlowNodeTypeEnum.CHECK, List.of("Muscle#1")),
                        new OcFlowNode("S2", OcFlowNodeTypeEnum.CHECK, List.of("Muscle#1")),
                        new OcFlowNode("G1", OcFlowNodeTypeEnum.GOOD_END, null),
                        new OcFlowNode("G2", OcFlowNodeTypeEnum.GOOD_END, null),
                        new OcFlowNode("B1", OcFlowNodeTypeEnum.BAD_END, null)),
                List.of(new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "S2"),
                        new OcFlowEdge("S1", OcFlowBranchEnum.FAIL, "B1"),
                        new OcFlowEdge("S2", OcFlowBranchEnum.PASS, "G1"),
                        new OcFlowEdge("S2", OcFlowBranchEnum.FAIL, "G2")));

        OcFlowOutcome outcome = evaluator.evaluate(graph, Map.of("Muscle#1", 0.5), 0.0);

        assertThat(outcome.successRate()).isCloseTo(0.5, within(1e-12));
        assertThat(outcome.endingRate("G1")).isCloseTo(0.25, within(1e-12));
        assertThat(outcome.endingRate("G2")).isCloseTo(0.25, within(1e-12));
        assertThat(outcome.endingRate("B1")).isCloseTo(0.5, within(1e-12));
    }

    @Test
    @DisplayName("参与者为空或未登记时使用缺省成功率")
    void evaluate_shouldFallBackToDefaultRate() {
        OcFlowGraph emptyParticipants = singleCheckGraph(
                new OcFlowNode("S1", OcFlowNodeTypeEnum.CHECK, List.of()));
        assertThat(evaluator.evaluate(emptyParticipants, Map.of(), 0.45).successRate())
                .isCloseTo(0.45, within(1e-12));

        OcFlowGraph unknownParticipant = singleCheckGraph(
                new OcFlowNode("S1", OcFlowNodeTypeEnum.CHECK, List.of("Muscle#1")));
        assertThat(evaluator.evaluate(unknownParticipant, Map.of("Thief#1", 0.9), 0.45).successRate())
                .isCloseTo(0.45, within(1e-12));
    }

    @Test
    @DisplayName("成功率超出0~1时拒绝求值，避免百分数被当成概率")
    void evaluate_shouldRejectOutOfRangeRate() {
        OcFlowGraph graph = singleCheckGraph(new OcFlowNode("S1", OcFlowNodeTypeEnum.CHECK, List.of("Muscle#1")));

        assertThatThrownBy(() -> evaluator.evaluate(graph, Map.of("Muscle#1", 72.0), 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成功率超出0~1范围");
        assertThatThrownBy(() -> evaluator.evaluate(graph, Map.of("Muscle#1", 0.5), 1.2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺省成功率");
    }

    @Test
    @DisplayName("Break the Bank 真实图与上游计算器逐组探针一致")
    void evaluate_shouldMatchUpstreamProbesOnBreakTheBank() throws IOException {
        JsonNode root = loadFixture();
        OcFlowGraph graph = toGraph(root);
        JsonNode probes = root.get("probes");

        Map<String, Integer> order = new HashMap<>();
        List<String> topologicalOrder = graph.topologicalOrder();
        for (int index = 0; index < topologicalOrder.size(); index++) {
            order.put(topologicalOrder.get(index), index);
        }
        for (JsonNode edge : root.get("edges")) {
            assertThat(order.get(edge.get("from").asText()))
                    .as("拓扑序必须先于边 %s->%s", edge.get("from").asText(), edge.get("to").asText())
                    .isLessThan(order.get(edge.get("to").asText()));
        }

        assertThat(probes.size()).as("探针数量").isEqualTo(7);
        List<String> mismatches = new ArrayList<>();
        for (JsonNode probe : probes) {
            Map<String, Double> slotRates = new HashMap<>();
            probe.get("slotRates").properties().forEach(entry ->
                    slotRates.put(entry.getKey(), entry.getValue().asDouble()));
            OcFlowOutcome outcome = evaluator.evaluate(graph, slotRates, 0.6);
            double upstream = probe.get("upstreamRate").asDouble();
            if (Math.abs(outcome.successRate() - upstream) > 1e-4) {
                mismatches.add(probe.get("vector").asText() + " 自建=" + outcome.successRate()
                        + " 上游=" + upstream);
            }
        }
        assertThat(mismatches).as("与上游计算器的成功率偏差").isEmpty();
    }

    private OcFlowGraph singleCheckGraph(OcFlowNode check) {
        return OcFlowGraph.of("Test", 8,
                List.of(check,
                        new OcFlowNode("G1", OcFlowNodeTypeEnum.GOOD_END, null),
                        new OcFlowNode("B1", OcFlowNodeTypeEnum.BAD_END, null)),
                List.of(new OcFlowEdge("S1", OcFlowBranchEnum.PASS, "G1"),
                        new OcFlowEdge("S1", OcFlowBranchEnum.FAIL, "B1")));
    }

    private JsonNode loadFixture() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/oc-flow/break-the-bank.json")) {
            assertThat(stream).as("缺少流程图测试数据").isNotNull();
            return new ObjectMapper().readTree(stream);
        }
    }

    private OcFlowGraph toGraph(JsonNode root) {
        List<OcFlowNode> nodes = new ArrayList<>();
        for (JsonNode node : root.get("nodes")) {
            List<String> participants = new ArrayList<>();
            JsonNode participantsNode = node.get("participants");
            if (participantsNode != null) {
                participantsNode.forEach(participant -> participants.add(participant.asText()));
            }
            nodes.add(new OcFlowNode(node.get("code").asText(),
                    OcFlowNodeTypeEnum.fromCode(node.get("type").asText()), participants));
        }
        List<OcFlowEdge> edges = new ArrayList<>();
        for (JsonNode edge : root.get("edges")) {
            edges.add(new OcFlowEdge(edge.get("from").asText(),
                    OcFlowBranchEnum.fromCode(edge.get("branch").asText()), edge.get("to").asText()));
        }
        return OcFlowGraph.of(root.get("ocName").asText(), root.get("rank").asInt(), nodes, edges);
    }
}
