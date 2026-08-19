package pn.torn.goldeneye.torn.service.faction.oc.planning.matching;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * OC最小费用最大流阵容匹配器。
 *
 * <p>负责成员岗位二分图匹配、残量网络求解和匹配后的准备时间线排序，
 * 不承担候选成员过滤及无停转回溯搜索。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
final class OcFlowRosterMatcher {
    private static final long WAIT_COST_FACTOR = 100L;
    private static final long CAPABILITY_COST_FACTOR = 20L;
    private static final long MARGIN_REWARD_FACTOR = 2L;
    private static final long COEFFICIENT_REWARD_FACTOR = 100L;

    /**
     * 执行岗位完整匹配并按指定排程策略生成成员准备时间线。
     *
     * @param demand                  队伍岗位需求
     * @param vacantSlots             空缺岗位
     * @param usableMembers           可用成员
     * @param planningTime            规划基准时间
     * @param mode                    规划模式
     * @param differentialWorkingHour 是否使用差异工时评价
     * @param deterministicSchedule   是否使用确定性岗位顺序
     * @return 完整匹配结果
     */
    OcRosterMatchResult match(OcTeamDemand demand,
                              List<OcPlanSlot> vacantSlots,
                              List<OcMemberCandidate> usableMembers,
                              LocalDateTime planningTime,
                              OcPlanMode mode,
                              boolean differentialWorkingHour,
                              boolean deterministicSchedule) {
        FlowGraph graph = buildGraph(demand, vacantSlots, usableMembers, planningTime, mode,
                differentialWorkingHour);
        int flow = graph.minCostMaxFlow(vacantSlots.size());
        if (flow != vacantSlots.size()) {
            return OcRosterMatchResult.failure(findMissingSlots(graph, vacantSlots,
                    usableMembers.size()));
        }
        List<MatchedSlot> matches = extractMatches(graph, demand, vacantSlots, usableMembers);
        if (deterministicSchedule) {
            List<MatchedSlot> deterministic = new ArrayList<>(matches);
            deterministic.sort(orderComparator(demand, differentialWorkingHour));
            List<OcPlannedAssignment> schedule = buildSchedule(deterministic, demand, planningTime);
            return schedule.isEmpty()
                    ? OcRosterMatchResult.failure(vacantSlots.stream().map(OcPlanSlot::code).toList())
                    : OcRosterMatchResult.success(schedule, schedule.getLast().stageCompleteAt());
        }
        return buildBestSchedule(matches, demand, planningTime, differentialWorkingHour);
    }

    /**
     * 构造成员到岗位的最小费用最大流网络。
     *
     * @param demand                  队伍岗位需求
     * @param slots                   空缺岗位
     * @param members                 可用成员
     * @param planningTime            规划基准时间
     * @param mode                    规划模式
     * @param differentialWorkingHour 是否使用差异工时评价
     * @return 流网络
     */
    private static FlowGraph buildGraph(OcTeamDemand demand, List<OcPlanSlot> slots,
                                        List<OcMemberCandidate> members,
                                        LocalDateTime planningTime, OcPlanMode mode,
                                        boolean differentialWorkingHour) {
        int source = 0;
        int memberOffset = 1;
        int slotOffset = memberOffset + members.size();
        int sink = slotOffset + slots.size();
        FlowGraph graph = new FlowGraph(sink + 1, source, sink, memberOffset, slotOffset);
        for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
            graph.addEdge(source, memberOffset + memberIndex, 0);
            OcMemberCandidate member = members.get(memberIndex);
            for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                OcPlanSlot slot = slots.get(slotIndex);
                int passRate = member.getPassRate(demand.rank(), demand.ocName(), slot.position());
                if (passRate < slot.requiredPassRate()) {
                    continue;
                }
                long cost = calculateEdgeCost(member, slot, demand, planningTime, passRate, mode,
                        differentialWorkingHour);
                graph.addEdge(memberOffset + memberIndex, slotOffset + slotIndex, cost);
            }
        }
        for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
            graph.addEdge(slotOffset + slotIndex, sink, 0);
        }
        return graph;
    }

    /**
     * 计算成员承担岗位的匹配费用。
     *
     * @param member                  候选成员
     * @param slot                    岗位需求
     * @param demand                  队伍需求
     * @param planningTime            规划基准时间
     * @param passRate                成员岗位通过率
     * @param mode                    规划模式
     * @param differentialWorkingHour 是否使用差异工时评价
     * @return 流网络边费用
     */
    private static long calculateEdgeCost(OcMemberCandidate member, OcPlanSlot slot,
                                          OcTeamDemand demand, LocalDateTime planningTime,
                                          int passRate, OcPlanMode mode,
                                          boolean differentialWorkingHour) {
        long waitHours = Math.max(0, Duration.between(planningTime, member.availableAt()).toHours());
        long opportunityCost = member.getCapabilityCount() * CAPABILITY_COST_FACTOR;
        long margin = (long) passRate - slot.requiredPassRate();
        long cost = waitHours * WAIT_COST_FACTOR + opportunityCost;
        if (OcPlanMode.CONSERVATIVE.equals(mode)) {
            cost -= margin * Math.max(1L, slot.priority()) * MARGIN_REWARD_FACTOR;
        } else if (OcPlanMode.PROFIT.equals(mode) && differentialWorkingHour) {
            BigDecimal coefficient = member.getCoefficient(demand.rank(), demand.ocName(), slot.code());
            cost -= coefficient.multiply(BigDecimal.valueOf(COEFFICIENT_REWARD_FACTOR)).longValue();
        } else {
            cost -= margin * Math.max(1L, slot.priority());
        }
        return cost;
    }

    /**
     * 从最大流结果中提取成员与岗位匹配关系。
     *
     * @param graph   已完成求流的残量网络
     * @param demand  队伍需求
     * @param slots   空缺岗位
     * @param members 可用成员
     * @return 成员岗位匹配集合
     */
    private static List<MatchedSlot> extractMatches(FlowGraph graph, OcTeamDemand demand,
                                                    List<OcPlanSlot> slots,
                                                    List<OcMemberCandidate> members) {
        List<MatchedSlot> result = new ArrayList<>(slots.size());
        for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
            int memberNode = graph.memberOffset + memberIndex;
            for (FlowEdge edge : graph.edges[memberNode]) {
                if (edge.originalCapacity != 1 || edge.capacity != 0
                        || edge.to < graph.slotOffset
                        || edge.to >= graph.slotOffset + slots.size()) {
                    continue;
                }
                OcMemberCandidate member = members.get(memberIndex);
                OcPlanSlot slot = slots.get(edge.to - graph.slotOffset);
                int passRate = member.getPassRate(demand.rank(), demand.ocName(), slot.position());
                result.add(new MatchedSlot(member, slot, passRate));
            }
        }
        return result;
    }

    /**
     * 从岗位匹配结果中选择完成时间和顺序评分最优的加入顺序。
     *
     * @param matches                 成员岗位匹配集合
     * @param demand                  队伍需求
     * @param planningTime            规划基准时间
     * @param differentialWorkingHour 是否使用差异工时评价
     * @return 最优完整排程
     */
    private static OcRosterMatchResult buildBestSchedule(List<MatchedSlot> matches, OcTeamDemand demand,
                                                         LocalDateTime planningTime,
                                                         boolean differentialWorkingHour) {
        if (matches.size() > 9) {
            List<MatchedSlot> deterministic = new ArrayList<>(matches);
            deterministic.sort(orderComparator(demand, differentialWorkingHour));
            List<OcPlannedAssignment> schedule = buildSchedule(deterministic, demand, planningTime);
            if (schedule.isEmpty()) {
                return OcRosterMatchResult.failure(demand.getVacantSlots().stream()
                        .map(OcPlanSlot::code).toList());
            }
            return OcRosterMatchResult.success(schedule, schedule.getLast().stageCompleteAt());
        }
        ScheduleScore best = null;
        for (List<MatchedSlot> order : permutations(matches)) {
            List<OcPlannedAssignment> schedule = buildSchedule(order, demand, planningTime);
            if (schedule.isEmpty()) {
                continue;
            }
            long orderScore = calculateOrderScore(order, demand, differentialWorkingHour);
            ScheduleScore score = new ScheduleScore(schedule, schedule.getLast().stageCompleteAt(),
                    orderScore);
            if (best == null || score.compareTo(best) < 0) {
                best = score;
            }
        }
        if (best == null) {
            return OcRosterMatchResult.failure(demand.getVacantSlots().stream()
                    .map(OcPlanSlot::code).toList());
        }
        return OcRosterMatchResult.success(best.assignments, best.completionAt);
    }

    /**
     * 构造确定性加入顺序比较器。
     *
     * @param demand                  队伍需求
     * @param differentialWorkingHour 是否使用差异工时评价
     * @return 加入顺序比较器
     */
    private static Comparator<MatchedSlot> orderComparator(OcTeamDemand demand,
                                                           boolean differentialWorkingHour) {
        return Comparator.comparingLong((MatchedSlot match) ->
                        -orderValue(match, demand, differentialWorkingHour))
                .thenComparingLong(match -> match.member.userId());
    }


    /**
     * 计算一组成员加入顺序的评分。
     *
     * @param order                   成员岗位顺序
     * @param demand                  队伍需求
     * @param differentialWorkingHour 是否使用差异工时评价
     * @return 顺序评分，越小越优
     */
    private static long calculateOrderScore(List<MatchedSlot> order, OcTeamDemand demand,
                                            boolean differentialWorkingHour) {
        long score = 0;
        int multiplier = order.size();
        for (MatchedSlot match : order) {
            score -= orderValue(match, demand, differentialWorkingHour) * multiplier--;
        }
        return score;
    }

    /**
     * 计算单个成员岗位匹配在加入顺序中的价值。
     *
     * @param match                   成员岗位匹配
     * @param demand                  队伍需求
     * @param differentialWorkingHour 是否使用差异工时评价
     * @return 顺序价值
     */
    private static long orderValue(MatchedSlot match, OcTeamDemand demand,
                                   boolean differentialWorkingHour) {
        if (differentialWorkingHour) {
            BigDecimal coefficient = match.member.getCoefficient(demand.rank(), demand.ocName(),
                    match.slot.code());
            return coefficient.multiply(BigDecimal.valueOf(100)).longValue();
        }
        long margin = (long) match.passRate - match.slot.requiredPassRate();
        return Math.max(1L, match.slot.priority()) * Math.max(1L, margin + 1);
    }

    /**
     * 按指定成员顺序构建准备阶段时间线。
     *
     * @param order        成员岗位顺序
     * @param demand       队伍需求
     * @param planningTime 规划基准时间
     * @return 成员加入安排；首人超期或数据异常时返回空集合
     */
    private static List<OcPlannedAssignment> buildSchedule(List<MatchedSlot> order, OcTeamDemand demand,
                                                           LocalDateTime planningTime) {
        List<OcPlannedAssignment> assignments = new ArrayList<>(order.size());
        if (!demand.fixedMemberIds().isEmpty() && demand.readyAt() == null) {
            return List.of();
        }
        LocalDateTime currentReadyTime = demand.readyAt();
        for (MatchedSlot match : order) {
            LocalDateTime joinAt = planningTime.isAfter(match.member.availableAt())
                    ? planningTime : match.member.availableAt();
            if (currentReadyTime == null && demand.expiresAt() != null
                    && joinAt.isAfter(demand.expiresAt())) {
                return List.of();
            }
            LocalDateTime completeAt = OcPreparationTimeCalculator.nextReadyTime(
                    currentReadyTime, joinAt);
            BigDecimal coefficient = match.member.getCoefficient(demand.rank(), demand.ocName(),
                    match.slot.code());
            assignments.add(new OcPlannedAssignment(match.member.userId(), match.member.nickname(),
                    match.slot.code(), match.passRate, match.slot.requiredPassRate(),
                    joinAt, completeAt, coefficient));
            currentReadyTime = completeAt;
        }
        return assignments;
    }

    /**
     * 根据残量网络识别未匹配岗位。
     *
     * @param graph       残量网络
     * @param slots       空缺岗位
     * @param memberCount 成员节点数量
     * @return 未匹配岗位编码
     */
    private static List<String> findMissingSlots(FlowGraph graph, List<OcPlanSlot> slots, int memberCount) {
        List<String> missing = new ArrayList<>();
        for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
            int node = graph.slotOffset + slotIndex;
            boolean matched = graph.edges[node].stream().anyMatch(edge -> edge.to >= graph.memberOffset
                    && edge.to < graph.memberOffset + memberCount && edge.capacity > 0);
            if (!matched) {
                missing.add(slots.get(slotIndex).code());
            }
        }
        return missing.isEmpty() ? slots.stream().map(OcPlanSlot::code).toList() : missing;
    }

    /**
     * 枚举成员岗位匹配的全部加入顺序。
     *
     * @param source 成员岗位匹配集合
     * @return 全排列集合
     */
    private static List<List<MatchedSlot>> permutations(List<MatchedSlot> source) {
        List<List<MatchedSlot>> result = new ArrayList<>();
        permute(new ArrayList<>(source), 0, result);
        return result;
    }

    /**
     * 原地交换并递归生成全排列。
     *
     * @param values 当前排列缓冲区
     * @param index  当前固定位置
     * @param result 全排列结果
     */
    private static void permute(List<MatchedSlot> values, int index, List<List<MatchedSlot>> result) {
        if (index == values.size()) {
            result.add(List.copyOf(values));
            return;
        }
        for (int i = index; i < values.size(); i++) {
            Collections.swap(values, index, i);
            permute(values, index + 1, result);
            Collections.swap(values, index, i);
        }
    }

    /**
     * 最小费用最大流选中的成员岗位匹配。
     *
     * @param member   成员
     * @param slot     岗位
     * @param passRate 岗位通过率
     */
    private record MatchedSlot(OcMemberCandidate member, OcPlanSlot slot, int passRate) {
    }

    /**
     * 一组成员加入顺序的排程评分。
     *
     * @param assignments  成员加入安排
     * @param completionAt 队伍最终完成时间
     * @param orderScore   顺序评分
     */
    private record ScheduleScore(List<OcPlannedAssignment> assignments, LocalDateTime completionAt,
                                 long orderScore) implements Comparable<ScheduleScore> {
        @Override
        public int compareTo(ScheduleScore other) {
            int timeResult = completionAt.compareTo(other.completionAt);
            if (timeResult != 0) {
                return timeResult;
            }
            return Long.compare(orderScore, other.orderScore);
        }
    }

    /**
     * 最小费用最大流残量网络。
     *
     * <p>该类型持有可变残量边数组，是算法状态对象，不使用record值语义。</p>
     */
    private static final class FlowGraph {
        private final List<FlowEdge>[] edges;
        private final int source;
        private final int sink;
        private final int memberOffset;
        private final int slotOffset;
        private int flow;

        /**
         * 创建包含固定节点分区的空残量网络。
         *
         * @param size         节点数量
         * @param source       源点
         * @param sink         汇点
         * @param memberOffset 成员节点起始偏移
         * @param slotOffset   岗位节点起始偏移
         */
        private FlowGraph(int size, int source, int sink, int memberOffset, int slotOffset) {
            this.edges = createEdges(size);
            this.source = source;
            this.sink = sink;
            this.memberOffset = memberOffset;
            this.slotOffset = slotOffset;
        }

        /**
         * 创建指定节点数的空邻接表。
         *
         * @param size 节点数量
         * @return 空邻接表
         */
        @SuppressWarnings("unchecked")
        private static List<FlowEdge>[] createEdges(int size) {
            List<FlowEdge>[] result = new List[size];
            Arrays.setAll(result, ignored -> new ArrayList<>());
            return result;
        }

        /**
         * 添加容量固定为1的正向边及其反向残量边。
         *
         * @param from 起点
         * @param to   终点
         * @param cost 单位费用
         */
        private void addEdge(int from, int to, long cost) {
            FlowEdge forward = new FlowEdge(to, edges[to].size(), 1, 1, cost);
            FlowEdge backward = new FlowEdge(from, edges[from].size(), 0, 0, -cost);
            edges[from].add(forward);
            edges[to].add(backward);
        }

        /**
         * 使用最短路增广计算指定目标流量的最小费用最大流。
         *
         * @param expectedFlow 目标流量
         * @return 实际完成流量
         */
        private int minCostMaxFlow(int expectedFlow) {
            while (flow < expectedFlow) {
                ShortestPath path = findShortestPath();
                if (!path.reaches(sink)) {
                    return flow;
                }
                augment(path);
                flow++;
            }
            return flow;
        }

        /**
         * 在当前残量网络中查找从源点到各节点的最短费用路径。
         *
         * @return 最短路径状态
         */
        private ShortestPath findShortestPath() {
            ShortestPath path = new ShortestPath(edges.length, source);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            path.markQueued(source);
            while (!queue.isEmpty()) {
                int node = queue.removeFirst();
                path.markDequeued(node);
                relaxEdges(node, path, queue);
            }
            return path;
        }

        /**
         * 尝试松弛指定节点的全部可用残量边。
         *
         * @param node  当前节点
         * @param path  最短路径状态
         * @param queue 待处理节点队列
         */
        private void relaxEdges(int node, ShortestPath path, ArrayDeque<Integer> queue) {
            for (int edgeIndex = 0; edgeIndex < edges[node].size(); edgeIndex++) {
                FlowEdge edge = edges[node].get(edgeIndex);
                if (path.canRelax(node, edge)) {
                    path.relax(node, edgeIndex, edge);
                    if (path.markQueued(edge.to)) {
                        queue.addLast(edge.to);
                    }
                }
            }
        }

        /**
         * 沿最短路径增广一个单位流量。
         *
         * @param path 最短路径状态
         */
        private void augment(ShortestPath path) {
            for (int node = sink; node != source; node = path.previousNode(node)) {
                int previousNode = path.previousNode(node);
                FlowEdge edge = edges[previousNode].get(path.previousEdge(node));
                edge.capacity--;
                edges[node].get(edge.reverseIndex).capacity++;
            }
        }
    }

    /**
     * 最短增广路搜索状态。
     */
    private static final class ShortestPath {
        private static final long UNREACHABLE_DISTANCE = Long.MAX_VALUE / 4;
        private final long[] distance;
        private final int[] previousNode;
        private final int[] previousEdge;
        private final boolean[] inQueue;

        /**
         * 创建最短路径状态并初始化源点距离。
         *
         * @param size   节点数量
         * @param source 源点
         */
        private ShortestPath(int size, int source) {
            this.distance = new long[size];
            Arrays.fill(distance, UNREACHABLE_DISTANCE);
            distance[source] = 0;
            this.previousNode = new int[size];
            this.previousEdge = new int[size];
            this.inQueue = new boolean[size];
        }

        /**
         * 判断残量边能否缩短目标节点距离。
         *
         * @param node 当前节点
         * @param edge 待检查残量边
         * @return 可松弛时返回true
         */
        private boolean canRelax(int node, FlowEdge edge) {
            return edge.capacity > 0
                    && distance[edge.to] > distance[node] + edge.cost;
        }

        /**
         * 使用指定残量边更新最短路径状态。
         *
         * @param node      当前节点
         * @param edgeIndex 当前节点的边索引
         * @param edge      被松弛的残量边
         */
        private void relax(int node, int edgeIndex, FlowEdge edge) {
            distance[edge.to] = distance[node] + edge.cost;
            previousNode[edge.to] = node;
            previousEdge[edge.to] = edgeIndex;
        }

        /**
         * 标记节点已进入队列。
         *
         * @param node 节点
         * @return 节点本次由未入队变为入队时返回true
         */
        private boolean markQueued(int node) {
            if (inQueue[node]) {
                return false;
            }
            inQueue[node] = true;
            return true;
        }

        /**
         * 标记节点已离开队列。
         *
         * @param node 节点
         */
        private void markDequeued(int node) {
            inQueue[node] = false;
        }

        /**
         * 判断目标节点是否可达。
         *
         * @param node 目标节点
         * @return 可达时返回true
         */
        private boolean reaches(int node) {
            return distance[node] < UNREACHABLE_DISTANCE / 2;
        }

        /**
         * 获取目标节点在最短路径中的前驱节点。
         *
         * @param node 目标节点
         * @return 前驱节点
         */
        private int previousNode(int node) {
            return previousNode[node];
        }

        /**
         * 获取目标节点对应的前驱边索引。
         *
         * @param node 目标节点
         * @return 前驱边索引
         */
        private int previousEdge(int node) {
            return previousEdge[node];
        }
    }

    /**
     * 残量网络中的有向边。
     *
     * <p>capacity会在增广过程中修改，因此不能建模为不可变record。</p>
     */
    private static final class FlowEdge {
        private final int to;
        private final int reverseIndex;
        private int capacity;
        private final int originalCapacity;
        private final long cost;

        /**
         * 创建残量边。
         *
         * @param to               终点
         * @param reverseIndex     反向边索引
         * @param capacity         当前剩余容量
         * @param originalCapacity 原始容量
         * @param cost             单位费用
         */
        private FlowEdge(int to, int reverseIndex, int capacity, int originalCapacity, long cost) {
            this.to = to;
            this.reverseIndex = reverseIndex;
            this.capacity = capacity;
            this.originalCapacity = originalCapacity;
            this.cost = cost;
        }
    }
}
