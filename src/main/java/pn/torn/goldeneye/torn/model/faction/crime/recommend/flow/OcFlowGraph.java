package pn.torn.goldeneye.torn.model.faction.crime.recommend.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OC流程图。
 *
 * <p>装配期一次性完成全部结构校验，把「配置写错」挡在求值之前：判定节点必须同时有PASS与FAIL出边、
 * 结局节点不得有出边、图必须无环且从起点全可达。校验失败的图在配置层告警后跳过，不进入推荐；
 * 求值期因此可以假定图是合法的，不需要在每个节点上写防御分支。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.24
 */
public final class OcFlowGraph {
    private final String ocName;
    private final Integer rank;
    private final Map<String, OcFlowNode> nodes;
    private final Map<String, Map<OcFlowBranchEnum, String>> transitions;
    private final String startNodeCode;
    private final List<String> topologicalOrder;

    private OcFlowGraph(String ocName, Integer rank, Map<String, OcFlowNode> nodes,
                        Map<String, Map<OcFlowBranchEnum, String>> transitions,
                        String startNodeCode, List<String> topologicalOrder) {
        this.ocName = ocName;
        this.rank = rank;
        this.nodes = Collections.unmodifiableMap(nodes);
        this.transitions = transitions;
        this.startNodeCode = startNodeCode;
        this.topologicalOrder = List.copyOf(topologicalOrder);
    }

    /**
     * 装配并校验一张流程图。
     *
     * @param ocName   OC名称
     * @param rank     OC等级
     * @param nodeList 节点列表
     * @param edgeList 边列表
     * @return 通过全部结构校验的流程图
     * @throws IllegalArgumentException 存在重复编码、悬空边、缺失分支、环或不可达节点
     */
    public static OcFlowGraph of(String ocName, Integer rank, List<OcFlowNode> nodeList, List<OcFlowEdge> edgeList) {
        String prefix = "OC流程图[" + ocName + "#" + rank + "] ";
        if (ocName == null || ocName.isBlank() || rank == null) {
            throw new IllegalArgumentException("OC名称与等级不能为空");
        }
        Map<String, OcFlowNode> nodes = buildNodes(nodeList, prefix);
        Map<String, Map<OcFlowBranchEnum, String>> transitions = buildTransitions(nodes, edgeList, prefix);
        String start = findStartNode(nodes, transitions, prefix);
        List<String> topologicalOrder = topologicalOrder(nodes, transitions, start, prefix);
        return new OcFlowGraph(ocName, rank, nodes, transitions, start, topologicalOrder);
    }

    /**
     * OC名称。
     *
     * @return OC名称
     */
    public String ocName() {
        return ocName;
    }

    /**
     * OC等级。
     *
     * @return OC等级
     */
    public Integer rank() {
        return rank;
    }

    /**
     * 全部节点。
     *
     * @return 节点编码到节点的只读映射
     */
    public Map<String, OcFlowNode> nodes() {
        return nodes;
    }

    /**
     * 起点节点编码。
     *
     * @return 起点节点编码
     */
    public String startNodeCode() {
        return startNodeCode;
    }

    /**
     * 拓扑序，起点在前。
     *
     * @return 拓扑序节点编码列表
     */
    public List<String> topologicalOrder() {
        return topologicalOrder;
    }

    /**
     * 查询节点。
     *
     * @param nodeCode 节点编码
     * @return 节点；不存在时返回null
     */
    public OcFlowNode node(String nodeCode) {
        return nodes.get(nodeCode);
    }

    /**
     * 查询指定分支的去向。
     *
     * @param nodeCode 起点节点编码
     * @param branch   分支
     * @return 去向节点编码；结局节点或分支缺失时返回null
     */
    public String targetOf(String nodeCode, OcFlowBranchEnum branch) {
        Map<OcFlowBranchEnum, String> branchMap = transitions.get(nodeCode);
        return branchMap == null ? null : branchMap.get(branch);
    }

    /**
     * 统计判定节点数量。
     *
     * @return 判定节点数量
     */
    public int checkCount() {
        return (int) nodes.values().stream().filter(OcFlowNode::isCheck).count();
    }

    /**
     * 装配节点并校验编码唯一。
     */
    private static Map<String, OcFlowNode> buildNodes(List<OcFlowNode> nodeList, String prefix) {
        if (nodeList == null || nodeList.isEmpty()) {
            throw new IllegalArgumentException(prefix + "节点为空");
        }
        Map<String, OcFlowNode> nodes = new LinkedHashMap<>(nodeList.size());
        for (OcFlowNode node : nodeList) {
            if (node == null || node.nodeCode() == null || node.nodeCode().isBlank()) {
                throw new IllegalArgumentException(prefix + "存在空节点编码");
            }
            if (node.nodeType() == null) {
                throw new IllegalArgumentException(prefix + "节点类型为空: " + node.nodeCode());
            }
            if (nodes.putIfAbsent(node.nodeCode(), node) != null) {
                throw new IllegalArgumentException(prefix + "节点编码重复: " + node.nodeCode());
            }
        }
        return nodes;
    }

    /**
     * 装配边并校验分支完整：判定恰好PASS与FAIL各一条，结局没有出边。
     */
    private static Map<String, Map<OcFlowBranchEnum, String>> buildTransitions(Map<String, OcFlowNode> nodes,
                                                                              List<OcFlowEdge> edgeList,
                                                                              String prefix) {
        Map<String, Map<OcFlowBranchEnum, String>> transitions = new HashMap<>();
        if (edgeList != null) {
            for (OcFlowEdge edge : edgeList) {
                if (edge == null || edge.branch() == null) {
                    throw new IllegalArgumentException(prefix + "存在缺少分支的边");
                }
                OcFlowNode from = nodes.get(edge.nodeCode());
                if (from == null) {
                    throw new IllegalArgumentException(prefix + "边起点不存在: " + edge.nodeCode());
                }
                if (!from.isCheck()) {
                    throw new IllegalArgumentException(prefix + "结局节点不应有出边: " + edge.nodeCode());
                }
                if (!nodes.containsKey(edge.targetNodeCode())) {
                    throw new IllegalArgumentException(prefix + "边终点不存在: " + edge.targetNodeCode());
                }
                Map<OcFlowBranchEnum, String> branchMap =
                        transitions.computeIfAbsent(edge.nodeCode(), key -> new EnumMap<>(OcFlowBranchEnum.class));
                if (branchMap.putIfAbsent(edge.branch(), edge.targetNodeCode()) != null) {
                    throw new IllegalArgumentException(prefix + "同一判定的分支重复: "
                            + edge.nodeCode() + "/" + edge.branch());
                }
            }
        }
        for (OcFlowNode node : nodes.values()) {
            Map<OcFlowBranchEnum, String> branchMap = transitions.get(node.nodeCode());
            if (node.isCheck() && (branchMap == null || branchMap.size() != OcFlowBranchEnum.values().length)) {
                throw new IllegalArgumentException(prefix + "判定缺少PASS或FAIL分支: " + node.nodeCode());
            }
            if (!node.isCheck() && branchMap != null) {
                throw new IllegalArgumentException(prefix + "结局节点不应有出边: " + node.nodeCode());
            }
        }
        return transitions;
    }

    /**
     * 定位唯一起点：没有任何入边的节点。
     */
    private static String findStartNode(Map<String, OcFlowNode> nodes,
                                        Map<String, Map<OcFlowBranchEnum, String>> transitions,
                                        String prefix) {
        Set<String> hasIncoming = new LinkedHashSet<>();
        for (Map<OcFlowBranchEnum, String> branchMap : transitions.values()) {
            hasIncoming.addAll(branchMap.values());
        }
        List<String> startCodes = new ArrayList<>();
        for (String nodeCode : nodes.keySet()) {
            if (!hasIncoming.contains(nodeCode)) {
                startCodes.add(nodeCode);
            }
        }
        if (startCodes.size() != 1) {
            throw new IllegalArgumentException(prefix + "起点数量应为1，实际为" + startCodes.size()
                    + ": " + startCodes);
        }
        return startCodes.getFirst();
    }

    /**
     * 深度优先遍历得到逆后序，同时完成成环与可达性校验。
     */
    private static List<String> topologicalOrder(Map<String, OcFlowNode> nodes,
                                                 Map<String, Map<OcFlowBranchEnum, String>> transitions,
                                                 String start, String prefix) {
        Map<String, Boolean> finished = new HashMap<>(nodes.size());
        List<String> reversePostOrder = new ArrayList<>(nodes.size());
        visit(start, transitions, finished, reversePostOrder, prefix);
        if (reversePostOrder.size() != nodes.size()) {
            Set<String> unreachable = new LinkedHashSet<>(nodes.keySet());
            unreachable.removeAll(reversePostOrder);
            throw new IllegalArgumentException(prefix + "存在从起点不可达的节点: " + unreachable);
        }
        Collections.reverse(reversePostOrder);
        return reversePostOrder;
    }

    /**
     * 单节点深度优先访问：灰色表示仍在递归栈上，命中过灰色节点即存在环。
     */
    private static void visit(String nodeCode, Map<String, Map<OcFlowBranchEnum, String>> transitions,
                              Map<String, Boolean> finished, List<String> reversePostOrder, String prefix) {
        Boolean state = finished.get(nodeCode);
        if (Boolean.FALSE.equals(state)) {
            throw new IllegalArgumentException(prefix + "存在环，回边指向: " + nodeCode);
        }
        if (Boolean.TRUE.equals(state)) {
            return;
        }
        finished.put(nodeCode, Boolean.FALSE);
        Map<OcFlowBranchEnum, String> branchMap = transitions.get(nodeCode);
        if (branchMap != null) {
            for (OcFlowBranchEnum branch : OcFlowBranchEnum.values()) {
                String target = branchMap.get(branch);
                if (target != null) {
                    visit(target, transitions, finished, reversePostOrder, prefix);
                }
            }
        }
        finished.put(nodeCode, Boolean.TRUE);
        reversePostOrder.add(nodeCode);
    }
}
