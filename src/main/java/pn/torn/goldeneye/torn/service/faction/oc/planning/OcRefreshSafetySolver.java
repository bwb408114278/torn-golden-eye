package pn.torn.goldeneye.torn.service.faction.oc.planning;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcMemberCandidate;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyRequest;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshVector;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTeamDemand;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在时间预算内证明普通池与高阶池的联合安全刷新边界。
 *
 * <p>求解器只把完整匹配成功的向量视为安全。超时或达到搜索上限时，
 * 返回已经证明的安全下界，不推测未验证向量。</p>
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
public class OcRefreshSafetySolver {
    private static final int CONDITIONAL_OC_EXPIRE_DAYS = 7;
    private final Duration timeout;
    private final int maxSearch;
    private final OcRosterMatcher rosterMatcher = new OcRosterMatcher();

    /**
     * 创建联合刷新安全边界求解器。
     *
     * @param timeout 单次求解时间预算
     * @param maxSearch 单个池的最大搜索次数
     */
    public OcRefreshSafetySolver(Duration timeout, int maxSearch) {
        this.timeout = timeout;
        this.maxSearch = maxSearch;
    }

    /**
     * 求解普通池与高阶池的联合安全前沿。
     *
     * @param request 当前计划时间线和随机池模板
     * @return 已证明安全的前沿向量
     */
    public OcRefreshSafetyResult solve(OcRefreshSafetyRequest request) {
        long startedAt = System.nanoTime();
        long deadline = startedAt + timeout.toNanos();
        BaseTimelineResult baseResult = prepareBaseTimeline(request);
        if (!baseResult.feasible()) {
            return result(startedAt, List.of(), false, List.of());
        }
        SearchState state = new SearchState();
        boolean timedOut = searchVectors(request, baseResult.timeline(), deadline, state);
        boolean lowerBound = timedOut || touchesSearchLimit(state.safe());
        if (timedOut) {
            state.warnings().add("刷新安全边界计算达到时间预算，仅返回已证明安全下界");
        } else if (lowerBound) {
            state.warnings().add("刷新安全边界达到搜索上限，仅返回已证明安全下界");
        }
        return result(startedAt, frontier(state.safe()), lowerBound, state.warnings());
    }

    /**
     * 按总刷新次数递增搜索全部普通池和高阶池向量。
     *
     * @param request 求解请求
     * @param baseTimeline 已完成现有计划义务的基础成员时间线
     * @param deadline 求解截止纳秒时间
     * @param state 搜索状态
     * @return 达到时间预算时返回true
     */
    private boolean searchVectors(OcRefreshSafetyRequest request,
                                  List<OcMemberCandidate> baseTimeline,
                                  long deadline, SearchState state) {
        for (int total = 0; total <= maxSearch * 2; total++) {
            if (searchTotal(request, baseTimeline, deadline, state, total)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 搜索指定总刷新次数下的全部普通池和高阶池分配。
     *
     * @param request 求解请求
     * @param baseTimeline 基础成员时间线
     * @param deadline 求解截止纳秒时间
     * @param state 搜索状态
     * @param total 总刷新次数
     * @return 达到时间预算时返回true
     */
    private boolean searchTotal(OcRefreshSafetyRequest request,
                                List<OcMemberCandidate> baseTimeline,
                                long deadline, SearchState state, int total) {
        int minHigh = Math.max(0, total - maxSearch);
        int maxHigh = Math.min(maxSearch, total);
        for (int high = minHigh; high <= maxHigh; high++) {
            OcRefreshVector vector = new OcRefreshVector(total - high, high);
            VectorEvaluation evaluation = evaluateVector(
                    request, vector, baseTimeline, deadline, state.failed());
            if (VectorEvaluation.TIMEOUT.equals(evaluation)) {
                return true;
            }
            addEvaluation(state, vector, evaluation);
        }
        return false;
    }

    /**
     * 验证单个刷新向量，并复用已失败子向量进行剪枝。
     *
     * @param request 求解请求
     * @param vector 待验证刷新向量
     * @param baseTimeline 基础成员时间线
     * @param deadline 求解截止纳秒时间
     * @param failed 已失败向量集合
     * @return 向量验证结果
     */
    private VectorEvaluation evaluateVector(OcRefreshSafetyRequest request,
                                            OcRefreshVector vector,
                                            List<OcMemberCandidate> baseTimeline,
                                            long deadline,
                                            List<OcRefreshVector> failed) {
        if (System.nanoTime() >= deadline) {
            return VectorEvaluation.TIMEOUT;
        }
        if (hasFailedSubset(vector, failed)) {
            return VectorEvaluation.FAILED;
        }
        boolean safe = isSafe(request, vector, baseTimeline, deadline);
        if (!safe && System.nanoTime() >= deadline) {
            return VectorEvaluation.TIMEOUT;
        }
        return safe ? VectorEvaluation.SAFE : VectorEvaluation.FAILED;
    }

    /**
     * 将向量验证结果写入搜索状态。
     *
     * @param state 搜索状态
     * @param vector 已验证向量
     * @param evaluation 验证结果
     */
    private void addEvaluation(SearchState state, OcRefreshVector vector,
                               VectorEvaluation evaluation) {
        if (VectorEvaluation.SAFE.equals(evaluation)) {
            state.safe().add(vector);
        } else {
            state.failed().add(vector);
        }
    }

    /**
     * 构造最终求解结果。
     *
     * @param startedAt 求解开始纳秒时间
     * @param safeFrontier 安全前沿
     * @param lowerBound 是否仅得到安全下界
     * @param warnings 求解警告
     * @return 求解结果
     */
    private OcRefreshSafetyResult result(long startedAt,
                                         List<OcRefreshVector> safeFrontier,
                                         boolean lowerBound,
                                         List<String> warnings) {
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return new OcRefreshSafetyResult(safeFrontier, lowerBound, elapsedMillis, warnings);
    }

    /**
     * 验证刷新向量覆盖的全部普通池和高阶池随机结果组合。
     *
     * @param request 求解请求
     * @param vector 待验证刷新向量
     * @param baseTimeline 基础成员时间线
     * @param deadline 求解截止纳秒时间
     * @return 全部随机结果组合均可安全排程时返回true
     */
    private boolean isSafe(OcRefreshSafetyRequest request, OcRefreshVector vector,
                           List<OcMemberCandidate> baseTimeline, long deadline) {
        if (hasMissingTemplate(request, vector)) {
            return false;
        }
        List<int[]> normalCombinations = nonEmptyCombinations(
                request.normalTemplates().size(), vector.normalCount());
        List<int[]> highCombinations = nonEmptyCombinations(
                request.highChains().size(), vector.highCount());
        return verifyCombinations(request, baseTimeline, deadline,
                normalCombinations, highCombinations);
    }

    /**
     * 判断待刷新池是否缺少计划模板。
     *
     * @param request 求解请求
     * @param vector 待验证刷新向量
     * @return 任一正次数刷新池缺少模板时返回true
     */
    private boolean hasMissingTemplate(OcRefreshSafetyRequest request,
                                       OcRefreshVector vector) {
        return vector.normalCount() > 0 && request.normalTemplates().isEmpty()
                || vector.highCount() > 0 && request.highChains().isEmpty();
    }

    /**
     * 验证普通池和高阶池组合的笛卡尔积。
     *
     * @param request 求解请求
     * @param baseTimeline 基础成员时间线
     * @param deadline 求解截止纳秒时间
     * @param normalCombinations 普通池组合
     * @param highCombinations 高阶池组合
     * @return 全部组合均可安全排程时返回true
     */
    private boolean verifyCombinations(OcRefreshSafetyRequest request,
                                       List<OcMemberCandidate> baseTimeline,
                                       long deadline,
                                       List<int[]> normalCombinations,
                                       List<int[]> highCombinations) {
        for (int[] normalCombination : normalCombinations) {
            for (int[] highCombination : highCombinations) {
                if (!verifyCombination(request, baseTimeline, deadline,
                        normalCombination, highCombination)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 验证一组普通池和高阶池随机结果。
     *
     * @param request 求解请求
     * @param baseTimeline 基础成员时间线
     * @param deadline 求解截止纳秒时间
     * @param normalCombination 普通池结果组合
     * @param highCombination 高阶池结果组合
     * @return 组合可安全排程时返回true
     */
    private boolean verifyCombination(OcRefreshSafetyRequest request,
                                      List<OcMemberCandidate> baseTimeline,
                                      long deadline,
                                      int[] normalCombination,
                                      int[] highCombination) {
        if (System.nanoTime() >= deadline) {
            return false;
        }
        List<OcMemberCandidate> timeline = copyMembers(baseTimeline);
        return scheduleNormalCombination(request, normalCombination, timeline)
                && scheduleHighCombination(request, highCombination, timeline);
    }

    /**
     * 计算组合列表，并为零类型零次数场景补充空组合。
     *
     * @param typeCount 随机结果类型数
     * @param total 刷新总次数
     * @return 组合列表
     */
    private List<int[]> nonEmptyCombinations(int typeCount, int total) {
        List<int[]> result = combinations(typeCount, total);
        return result.isEmpty() ? List.of(new int[0]) : result;
    }

    /**
     * 调度当前计划义务，生成所有候选随机结果共享的基础时间线。
     *
     * @param request 求解请求
     * @return 基础时间线构造结果
     */
    private BaseTimelineResult prepareBaseTimeline(OcRefreshSafetyRequest request) {
        List<OcMemberCandidate> timeline = copyMembers(request.members());
        boolean feasible = scheduleDemands(request.baseDemands(), timeline, request.planningTime())
                && scheduleBaseChains(request.baseChains(), timeline, request.planningTime());
        return new BaseTimelineResult(feasible, feasible ? timeline : List.of());
    }

    /**
     * 判断当前向量是否包含已失败的子向量。
     *
     * @param vector 当前刷新向量
     * @param failed 已失败向量集合
     * @return 存在失败子向量时返回true
     */
    private boolean hasFailedSubset(OcRefreshVector vector, List<OcRefreshVector> failed) {
        return failed.stream().anyMatch(item -> item.normalCount() <= vector.normalCount()
                && item.highCount() <= vector.highCount());
    }

    /**
     * 判断已证明安全的向量是否触及单池搜索上限。
     *
     * @param safe 已证明安全的向量集合
     * @return 任一池次数达到搜索上限时返回true
     */
    private boolean touchesSearchLimit(List<OcRefreshVector> safe) {
        return safe.stream().anyMatch(vector -> vector.normalCount() == maxSearch
                || vector.highCount() == maxSearch);
    }

    /**
     * 从全部安全向量中提取非支配安全前沿。
     *
     * @param safe 全部已证明安全的向量
     * @return 非支配安全前沿
     */
    private List<OcRefreshVector> frontier(List<OcRefreshVector> safe) {
        return safe.stream().filter(candidate -> safe.stream().noneMatch(other ->
                        other != candidate
                                && other.normalCount() >= candidate.normalCount()
                                && other.highCount() >= candidate.highCount()
                                && (other.normalCount() > candidate.normalCount()
                                || other.highCount() > candidate.highCount())))
                .sorted(Comparator.comparingInt(OcRefreshVector::normalCount)
                        .thenComparingInt(OcRefreshVector::highCount))
                .toList();
    }

    /**
     * 枚举指定次数在随机结果类型之间的全部非负整数分配。
     *
     * @param typeCount 随机结果类型数
     * @param total 刷新总次数
     * @return 计数组合列表
     */
    private List<int[]> combinations(int typeCount, int total) {
        List<int[]> result = new ArrayList<>();
        if (typeCount == 0) {
            if (total == 0) {
                result.add(new int[0]);
            }
            return result;
        }
        buildCombinations(result, new int[typeCount], 0, total);
        return result;
    }

    /**
     * 递归构造随机结果计数组合。
     *
     * @param result 组合结果集合
     * @param current 当前组合缓冲区
     * @param index 当前类型索引
     * @param remaining 尚未分配的次数
     */
    private void buildCombinations(List<int[]> result, int[] current,
                                   int index, int remaining) {
        if (index == current.length - 1) {
            current[index] = remaining;
            result.add(current.clone());
            return;
        }
        for (int value = 0; value <= remaining; value++) {
            current[index] = value;
            buildCombinations(result, current, index + 1, remaining - value);
        }
    }

    /**
     * 复制成员时间线，避免不同随机结果组合相互污染。
     *
     * @param members 原成员时间线
     * @return 可独立修改的成员时间线
     */
    private List<OcMemberCandidate> copyMembers(List<OcMemberCandidate> members) {
        return new ArrayList<>(members.stream().map(member -> member.withAvailability(
                member.availableAt(), member.fixed())).toList());
    }

    /**
     * 按首人期限、阶段时间、等级和名称的稳定顺序调度需求集合。
     *
     * @param demands 队伍需求集合
     * @param timeline 当前成员时间线
     * @param planningTime 规划基准时间
     * @return 全部需求均可完整排程时返回true
     */
    private boolean scheduleDemands(List<OcTeamDemand> demands,
                                    List<OcMemberCandidate> timeline,
                                    LocalDateTime planningTime) {
        List<OcTeamDemand> ordered = demands.stream()
                .sorted(Comparator.comparing(OcTeamDemand::expiresAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(OcTeamDemand::readyAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Comparator.comparingInt(OcTeamDemand::rank).reversed())
                        .thenComparing(OcTeamDemand::ocName))
                .toList();
        for (OcTeamDemand demand : ordered) {
            if (!scheduleDemand(demand, timeline, planningTime)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 调度一个普通池随机结果组合。
     *
     * @param request 求解请求
     * @param combination 普通池各模板出现次数
     * @param timeline 当前成员时间线
     * @return 组合可完整排程时返回true
     */
    private boolean scheduleNormalCombination(OcRefreshSafetyRequest request,
                                               int[] combination,
                                               List<OcMemberCandidate> timeline) {
        List<OcTeamDemand> demands = new ArrayList<>();
        for (int index = 0; index < combination.length; index++) {
            OcTeamDemand template = request.normalTemplates().get(index);
            for (int count = 0; count < combination[index]; count++) {
                demands.add(freshDemand(template, request.planningTime()));
            }
        }
        return scheduleDemands(demands, timeline, request.planningTime());
    }

    /**
     * 调度当前已存在的计划内高阶根及其后继义务。
     *
     * @param chains 当前计划内高阶链
     * @param timeline 当前成员时间线
     * @param planningTime 规划基准时间
     * @return 全部高阶链可完整排程时返回true
     */
    private boolean scheduleBaseChains(List<List<OcTeamDemand>> chains,
                                       List<OcMemberCandidate> timeline,
                                       LocalDateTime planningTime) {
        for (List<OcTeamDemand> chain : chains) {
            if (!chain.isEmpty() && !scheduleBaseChain(chain, timeline, planningTime)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 调度单条当前计划内高阶链。
     *
     * @param chain 高阶链节点需求
     * @param timeline 当前成员时间线
     * @param planningTime 规划基准时间
     * @return 根及全部后继均可完整排程时返回true
     */
    private boolean scheduleBaseChain(List<OcTeamDemand> chain,
                                      List<OcMemberCandidate> timeline,
                                      LocalDateTime planningTime) {
        OcTeamDemand root = chain.getFirst();
        OcRosterMatchResult rootMatch = rosterMatcher.matchDeterministic(
                root, timeline, planningTime);
        if (isIncomplete(rootMatch)) {
            return false;
        }
        updateTimeline(timeline, rootMatch, root);
        LocalDateTime nodeStart = rootMatch.completionAt();
        for (int index = 1; index < chain.size(); index++) {
            OcTeamDemand successor = freshDemand(chain.get(index), nodeStart);
            OcRosterMatchResult match = rosterMatcher.matchDeterministic(
                    successor, timeline, nodeStart);
            if (isIncomplete(match)) {
                return false;
            }
            updateTimeline(timeline, match, successor);
            nodeStart = match.completionAt();
        }
        return true;
    }

    /**
     * 调度一个高阶池随机链组合。
     *
     * @param request 求解请求
     * @param combination 各高阶链出现次数
     * @param timeline 当前成员时间线
     * @return 组合可完整排程时返回true
     */
    private boolean scheduleHighCombination(OcRefreshSafetyRequest request, int[] combination,
                                            List<OcMemberCandidate> timeline) {
        if (combination.length == 0) {
            return true;
        }
        for (int chainIndex = 0; chainIndex < combination.length; chainIndex++) {
            List<OcTeamDemand> chain = request.highChains().get(chainIndex);
            for (int count = 0; count < combination[chainIndex]; count++) {
                LocalDateTime nodeStart = request.planningTime();
                for (OcTeamDemand template : chain) {
                    OcTeamDemand demand = freshDemand(template, nodeStart);
                    OcRosterMatchResult match = rosterMatcher.matchDeterministic(
                            demand, timeline, nodeStart);
                    if (isIncomplete(match)) {
                        return false;
                    }
                    updateTimeline(timeline, match, demand);
                    nodeStart = match.completionAt();
                }
            }
        }
        return true;
    }

    /**
     * 调度单支OC并将已分配成员推进到最终完成时间。
     *
     * @param demand 队伍需求
     * @param timeline 当前成员时间线
     * @param planningTime 规划基准时间
     * @return 可形成完整阵容时返回true
     */
    private boolean scheduleDemand(OcTeamDemand demand,
                                   List<OcMemberCandidate> timeline,
                                   LocalDateTime planningTime) {
        OcRosterMatchResult match = rosterMatcher.matchDeterministic(
                demand, timeline, planningTime);
        if (isIncomplete(match)) {
            return false;
        }
        updateTimeline(timeline, match, demand);
        return true;
    }

    /**
     * 判断岗位匹配结果是否不完整。
     *
     * @param match 岗位匹配结果
     * @return 未完成完整阵容或缺少完成时间时返回true
     */
    private boolean isIncomplete(OcRosterMatchResult match) {
        return !match.complete() || match.completionAt() == null;
    }

    /**
     * 将新分配成员和已有固定成员统一释放到队伍最终完成时间。
     *
     * @param timeline 当前成员时间线
     * @param match 完整岗位匹配结果
     * @param demand 当前队伍需求
     */
    private void updateTimeline(List<OcMemberCandidate> timeline,
                                OcRosterMatchResult match,
                                OcTeamDemand demand) {
        Map<Long, OcMemberCandidate> state = new HashMap<>();
        timeline.forEach(member -> state.put(member.userId(), member));
        match.assignments().forEach(assignment -> {
            OcMemberCandidate member = state.get(assignment.userId());
            if (member != null) {
                state.put(member.userId(), member.withAvailability(match.completionAt(), false));
            }
        });
        demand.fixedMemberIds().forEach(userId -> {
            OcMemberCandidate member = state.get(userId);
            if (member != null) {
                state.put(userId, member.withAvailability(match.completionAt(), false));
            }
        });
        timeline.clear();
        timeline.addAll(state.values().stream()
                .sorted(Comparator.comparingLong(OcMemberCandidate::userId)).toList());
    }

    /**
     * 根据随机结果模板创建新的无人OC需求。
     *
     * @param template 随机结果模板
     * @param createdAt OC创建时间
     * @return 带首人加入期限的新需求
     */
    private OcTeamDemand freshDemand(OcTeamDemand template, LocalDateTime createdAt) {
        return new OcTeamDemand(0L, template.ocName(), template.rank(), null,
                createdAt.plusDays(CONDITIONAL_OC_EXPIRE_DAYS), template.chain(),
                template.slots(), Set.of(), Set.of());
    }

    /**
     * 单个刷新向量的验证状态。
     */
    private enum VectorEvaluation {
        SAFE,
        FAILED,
        TIMEOUT
    }

    /**
     * 当前计划义务调度后的基础成员时间线。
     *
     * @param feasible 当前计划义务是否可完整排程
     * @param timeline 基础成员时间线
     */
    private record BaseTimelineResult(boolean feasible,
                                      List<OcMemberCandidate> timeline) {
    }

    /**
     * 联合安全边界搜索过程中的可变状态。
     */
    private static final class SearchState {
        private final List<OcRefreshVector> safe = new ArrayList<>();
        private final List<OcRefreshVector> failed = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        /**
         * 获取已证明安全的向量。
         *
         * @return 安全向量集合
         */
        private List<OcRefreshVector> safe() {
            return safe;
        }

        /**
         * 获取已证明失败的向量。
         *
         * @return 失败向量集合
         */
        private List<OcRefreshVector> failed() {
            return failed;
        }

        /**
         * 获取求解警告。
         *
         * @return 警告集合
         */
        private List<String> warnings() {
            return warnings;
        }
    }
}
