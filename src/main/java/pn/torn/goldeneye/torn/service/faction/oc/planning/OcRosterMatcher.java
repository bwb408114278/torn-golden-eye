package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanSlot;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlannedAssignment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一支OC的精确人员岗位匹配器。
 *
 * <p>使用最小费用最大流完成全局二分图匹配，不按岗位逐个贪心，
 * 避免把唯一能完成稀缺岗位的成员提前消耗在普通岗位。</p>
 */
public class OcRosterMatcher {
    private static final int HOURS_PER_STAGE = 24;
    private static final long WAIT_COST_FACTOR = 100L;
    private static final long CAPABILITY_COST_FACTOR = 20L;
    private static final long MARGIN_REWARD_FACTOR = 2L;
    private static final long COEFFICIENT_REWARD_FACTOR = 100L;
    private static final int INF_CAPACITY = 1_000_000;

    public OcRosterMatchResult match(OcTeamDemand demand, List<OcMemberCandidate> candidates,
                                     LocalDateTime planningTime) {
        return match(demand, candidates, planningTime, OcPlanMode.BALANCED, false);
    }

    public OcRosterMatchResult match(OcTeamDemand demand, List<OcMemberCandidate> candidates,
                                     LocalDateTime planningTime, OcPlanMode mode,
                                     boolean differentialWorkingHour) {
        List<OcPlanSlot> vacantSlots = demand.getVacantSlots();
        if (vacantSlots.isEmpty()) {
            LocalDateTime completionAt = demand.readyAt() == null ? planningTime : demand.readyAt();
            return OcRosterMatchResult.success(List.of(), completionAt);
        }
        List<OcMemberCandidate> usableMembers = candidates.stream()
                .filter(candidate -> !candidate.fixed())
                .filter(candidate -> !demand.fixedMemberIds().contains(candidate.userId()))
                .sorted(Comparator.comparing(OcMemberCandidate::availableAt)
                        .thenComparingLong(OcMemberCandidate::userId))
                .toList();
        if (usableMembers.size() < vacantSlots.size()) {
            return OcRosterMatchResult.failure(vacantSlots.stream().map(OcPlanSlot::code).toList());
        }

        FlowGraph graph = buildGraph(demand, vacantSlots, usableMembers, planningTime, mode,
                differentialWorkingHour);
        int flow = graph.minCostMaxFlow(vacantSlots.size());
        if (flow != vacantSlots.size()) {
            return OcRosterMatchResult.failure(findMissingSlots(graph, vacantSlots,
                    usableMembers.size()));
        }
        List<MatchedSlot> matches = extractMatches(graph, demand, vacantSlots, usableMembers);
        return buildBestSchedule(matches, demand, planningTime, mode, differentialWorkingHour);
    }

    private FlowGraph buildGraph(OcTeamDemand demand, List<OcPlanSlot> slots,
                                 List<OcMemberCandidate> members, LocalDateTime planningTime,
                                 OcPlanMode mode, boolean differentialWorkingHour) {
        int source = 0;
        int memberOffset = 1;
        int slotOffset = memberOffset + members.size();
        int sink = slotOffset + slots.size();
        FlowGraph graph = new FlowGraph(sink + 1, source, sink, memberOffset, slotOffset);
        for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
            graph.addEdge(source, memberOffset + memberIndex, 1, 0);
            OcMemberCandidate member = members.get(memberIndex);
            for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                OcPlanSlot slot = slots.get(slotIndex);
                int passRate = member.getPassRate(demand.rank(), demand.ocName(), slot.position());
                if (passRate < slot.requiredPassRate()) {
                    continue;
                }
                long cost = calculateEdgeCost(member, slot, demand, planningTime, passRate, mode,
                        differentialWorkingHour);
                graph.addEdge(memberOffset + memberIndex, slotOffset + slotIndex, 1, cost);
            }
        }
        for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
            graph.addEdge(slotOffset + slotIndex, sink, 1, 0);
        }
        return graph;
    }

    private long calculateEdgeCost(OcMemberCandidate member, OcPlanSlot slot,
                                   OcTeamDemand demand, LocalDateTime planningTime, int passRate,
                                   OcPlanMode mode, boolean differentialWorkingHour) {
        long waitHours = Math.max(0, Duration.between(planningTime, member.availableAt()).toHours());
        long opportunityCost = (long) member.getCapabilityCount() * CAPABILITY_COST_FACTOR;
        long margin = passRate - slot.requiredPassRate();
        long cost = waitHours * WAIT_COST_FACTOR + opportunityCost;
        if (OcPlanMode.CONSERVATIVE.equals(mode)) {
            cost -= margin * Math.max(1, slot.priority()) * MARGIN_REWARD_FACTOR;
        } else if (OcPlanMode.PROFIT.equals(mode) && differentialWorkingHour) {
            BigDecimal coefficient = member.getCoefficient(demand.rank(), demand.ocName(), slot.code());
            cost -= coefficient.multiply(BigDecimal.valueOf(COEFFICIENT_REWARD_FACTOR)).longValue();
        } else {
            cost -= margin * Math.max(1, slot.priority());
        }
        return cost;
    }

    private List<MatchedSlot> extractMatches(FlowGraph graph, OcTeamDemand demand,
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

    private OcRosterMatchResult buildBestSchedule(List<MatchedSlot> matches, OcTeamDemand demand,
                                                  LocalDateTime planningTime, OcPlanMode mode,
                                                  boolean differentialWorkingHour) {
        if (matches.size() > 9) {
            List<MatchedSlot> deterministic = new ArrayList<>(matches);
            deterministic.sort(orderComparator(demand, mode, differentialWorkingHour));
            List<OcPlannedAssignment> schedule = buildSchedule(deterministic, demand, planningTime);
            return OcRosterMatchResult.success(schedule, schedule.getLast().stageCompleteAt());
        }
        ScheduleScore best = null;
        for (List<MatchedSlot> order : permutations(matches)) {
            List<OcPlannedAssignment> schedule = buildSchedule(order, demand, planningTime);
            long orderScore = calculateOrderScore(order, demand, mode, differentialWorkingHour);
            ScheduleScore score = new ScheduleScore(schedule, schedule.getLast().stageCompleteAt(),
                    orderScore);
            if (best == null || score.compareTo(best) < 0) {
                best = score;
            }
        }
        return OcRosterMatchResult.success(best.assignments, best.completionAt);
    }

    private Comparator<MatchedSlot> orderComparator(OcTeamDemand demand, OcPlanMode mode,
                                                    boolean differentialWorkingHour) {
        return Comparator.comparingLong((MatchedSlot match) ->
                        -orderValue(match, demand, mode, differentialWorkingHour))
                .thenComparingLong(match -> match.member.userId());
    }

    private long calculateOrderScore(List<MatchedSlot> order, OcTeamDemand demand, OcPlanMode mode,
                                     boolean differentialWorkingHour) {
        long score = 0;
        int multiplier = order.size();
        for (MatchedSlot match : order) {
            score -= orderValue(match, demand, mode, differentialWorkingHour) * multiplier--;
        }
        return score;
    }

    private long orderValue(MatchedSlot match, OcTeamDemand demand, OcPlanMode mode,
                            boolean differentialWorkingHour) {
        if (differentialWorkingHour) {
            BigDecimal coefficient = match.member.getCoefficient(demand.rank(), demand.ocName(),
                    match.slot.code());
            return coefficient.multiply(BigDecimal.valueOf(100)).longValue();
        }
        long margin = match.passRate - match.slot.requiredPassRate();
        return (long) Math.max(1, match.slot.priority()) * Math.max(1, margin + 1);
    }

    private List<OcPlannedAssignment> buildSchedule(List<MatchedSlot> order, OcTeamDemand demand,
                                                    LocalDateTime planningTime) {
        List<OcPlannedAssignment> assignments = new ArrayList<>(order.size());
        LocalDateTime stageCursor = demand.readyAt() == null || demand.readyAt().isBefore(planningTime)
                ? planningTime : demand.readyAt();
        for (MatchedSlot match : order) {
            LocalDateTime joinAt = stageCursor.isAfter(match.member.availableAt())
                    ? stageCursor : match.member.availableAt();
            LocalDateTime completeAt = joinAt.plusHours(HOURS_PER_STAGE);
            BigDecimal coefficient = match.member.getCoefficient(demand.rank(), demand.ocName(),
                    match.slot.code());
            assignments.add(new OcPlannedAssignment(match.member.userId(), match.member.nickname(),
                    match.slot.code(), match.passRate, match.slot.requiredPassRate(),
                    joinAt, completeAt, coefficient));
            stageCursor = completeAt;
        }
        return assignments;
    }

    private List<String> findMissingSlots(FlowGraph graph, List<OcPlanSlot> slots, int memberCount) {
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

    private List<List<MatchedSlot>> permutations(List<MatchedSlot> source) {
        List<List<MatchedSlot>> result = new ArrayList<>();
        permute(new ArrayList<>(source), 0, result);
        return result;
    }

    private void permute(List<MatchedSlot> values, int index, List<List<MatchedSlot>> result) {
        if (index == values.size()) {
            result.add(List.copyOf(values));
            return;
        }
        for (int i = index; i < values.size(); i++) {
            java.util.Collections.swap(values, index, i);
            permute(values, index + 1, result);
            java.util.Collections.swap(values, index, i);
        }
    }

    private record MatchedSlot(OcMemberCandidate member, OcPlanSlot slot, int passRate) {
    }

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

    private static final class FlowGraph {
        private final List<FlowEdge>[] edges;
        private final int source;
        private final int sink;
        private final int memberOffset;
        private final int slotOffset;

        @SuppressWarnings("unchecked")
        private FlowGraph(int size, int source, int sink, int memberOffset, int slotOffset) {
            this.edges = new List[size];
            Arrays.setAll(this.edges, ignored -> new ArrayList<>());
            this.source = source;
            this.sink = sink;
            this.memberOffset = memberOffset;
            this.slotOffset = slotOffset;
        }

        private void addEdge(int from, int to, int capacity, long cost) {
            FlowEdge forward = new FlowEdge(to, edges[to].size(), capacity, capacity, cost);
            FlowEdge backward = new FlowEdge(from, edges[from].size(), 0, 0, -cost);
            edges[from].add(forward);
            edges[to].add(backward);
        }

        private int minCostMaxFlow(int expectedFlow) {
            int flow = 0;
            while (flow < expectedFlow) {
                long[] distance = new long[edges.length];
                Arrays.fill(distance, Long.MAX_VALUE / 4);
                distance[source] = 0;
                int[] previousNode = new int[edges.length];
                int[] previousEdge = new int[edges.length];
                boolean[] inQueue = new boolean[edges.length];
                java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
                queue.add(source);
                inQueue[source] = true;
                while (!queue.isEmpty()) {
                    int node = queue.removeFirst();
                    inQueue[node] = false;
                    for (int edgeIndex = 0; edgeIndex < edges[node].size(); edgeIndex++) {
                        FlowEdge edge = edges[node].get(edgeIndex);
                        if (edge.capacity <= 0 || distance[edge.to] <= distance[node] + edge.cost) {
                            continue;
                        }
                        distance[edge.to] = distance[node] + edge.cost;
                        previousNode[edge.to] = node;
                        previousEdge[edge.to] = edgeIndex;
                        if (!inQueue[edge.to]) {
                            queue.addLast(edge.to);
                            inQueue[edge.to] = true;
                        }
                    }
                }
                if (distance[sink] >= Long.MAX_VALUE / 8) {
                    break;
                }
                for (int node = sink; node != source; node = previousNode[node]) {
                    FlowEdge edge = edges[previousNode[node]].get(previousEdge[node]);
                    edge.capacity--;
                    edges[node].get(edge.reverseIndex).capacity++;
                }
                flow++;
            }
            return flow;
        }
    }

    private static final class FlowEdge {
        private final int to;
        private final int reverseIndex;
        private int capacity;
        private final int originalCapacity;
        private final long cost;

        private FlowEdge(int to, int reverseIndex, int capacity, int originalCapacity, long cost) {
            this.to = to;
            this.reverseIndex = reverseIndex;
            this.capacity = capacity;
            this.originalCapacity = originalCapacity;
            this.cost = cost;
        }
    }
}
