package pn.torn.goldeneye.torn.service.faction.oc.planning.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.service.faction.oc.planning.policy.OcRefreshModeSelector;
import pn.torn.goldeneye.torn.service.faction.oc.planning.search.OcRefreshSafetySolver;
import pn.torn.goldeneye.torn.service.faction.oc.planning.snapshot.OcCurrentOccupancyCalculator;
import pn.torn.goldeneye.torn.service.faction.oc.planning.snapshot.OcRefreshSafetyRequestFactory;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcReplanWindowCalculator;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelinePolicy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 基于不可变快照生成匿名刷新指令的纯规划器。
 *
 * <p>同时输出匿名结构化Shadow日志，日志不包含成员、岗位、内部排程或奖励明细。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcRefreshInstructionPlanner {
    /**
     * 搜索墙钟超时。NOV/PN真实只读回放显示单次求解最坏约3.2秒且由确定性组合
     * 预算先截断；5秒保留必要余量，仅作兜底，不作为常规截断手段。
     */
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_REFRESH_SEARCH_COUNT = 20;

    private final OcRefreshSafetyRequestFactory requestFactory;
    private final OcRefreshModeSelector modeSelector;
    private final OcCurrentOccupancyCalculator occupancyCalculator;
    private final OcReplanWindowCalculator replanWindowCalculator;

    /**
     * 生成指定模式的刷新指令。
     *
     * @param snapshot 同一规划周期内的不可变快照
     * @param mode     刷新策略模式
     * @return 不包含成员分配的刷新操作指令
     */
    public OcRefreshInstructionPlan plan(OcPlanningSnapshot snapshot, OcPlanMode mode) {
        return plan(snapshot, mode, false);
    }

    /**
     * 生成指定模式的刷新指令，并可标记本次规划前随机结果已确认变化。
     *
     * <p>{@code randomOutcomeRefreshed}的调用前置条件是已确认的游戏随机结果状态变化事件，
     * 不是完成本地OC数据同步；完成本地同步后调用方必须传{@code false}。
     * 仅在已确认随机结果改变时，旧建议才立即失效，
     * 重评估窗口收敛为立即重评估并输出随机结果变化原因码。</p>
     *
     * @param snapshot               同一规划周期内的不可变快照
     * @param mode                   刷新策略模式
     * @param randomOutcomeRefreshed 本次规划前是否已确认Torn随机结果发生变化
     * @return 不包含成员分配的刷新操作指令
     */
    public OcRefreshInstructionPlan plan(OcPlanningSnapshot snapshot, OcPlanMode mode,
                                         boolean randomOutcomeRefreshed) {
        OcRefreshPlanningContext context = requestFactory.create(snapshot);
        boolean configurationValid = context.configurationStatus() == OcConfigurationStatusEnum.VALID;
        Map<String, OcValueEvidence> evidence = requestFactory.buildEvidenceByTemplate(context,
                snapshot);
        OcRefreshSafetyResult safety = configurationValid
                ? new OcRefreshSafetySolver(SEARCH_TIMEOUT, MAX_REFRESH_SEARCH_COUNT)
                .solve(context.request(), evidence, context.riskFlags(), context.reasonCodes())
                : notEvaluatedResult(context);
        Optional<SafeCandidate> selected = configurationValid
                ? modeSelector.selectCandidate(safety, mode) : Optional.empty();
        OcCurrentOccupancySummary occupancySummary = occupancyCalculator.calculate(snapshot);
        return buildPlan(snapshot, mode, context, safety, selected, occupancySummary,
                randomOutcomeRefreshed);
    }

    /**
     * 构造配置无效时未参与求解的空结果。
     *
     * @param context 刷新规划上下文
     * @return 证明状态为未求解的空结果
     */
    private OcRefreshSafetyResult notEvaluatedResult(OcRefreshPlanningContext context) {
        OcTimelineSafetyAssessment assessment = new OcTimelineSafetyAssessment(
                context.configurationStatus(), OcProofStatusEnum.NOT_EVALUATED,
                context.riskFlags(), false, context.reasonCodes(), List.of(), null,
                context.request().planningTime());
        return new OcRefreshSafetyResult(assessment, List.of(), false, 0,
                OcSearchTelemetry.empty(), context.warnings());
    }

    /**
     * 组装最终匿名刷新指令。
     *
     * @param snapshot               规划快照
     * @param mode                   刷新策略模式
     * @param context                刷新规划上下文
     * @param safety                 时间线求解结果
     * @param selected               已选安全候选
     * @param occupancySummary       当前现实占用摘要
     * @param randomOutcomeRefreshed 本次规划前是否刚从Torn刷新随机结果
     * @return 匿名刷新指令
     */
    private OcRefreshInstructionPlan buildPlan(OcPlanningSnapshot snapshot, OcPlanMode mode,
                                               OcRefreshPlanningContext context,
                                               OcRefreshSafetyResult safety,
                                               Optional<SafeCandidate> selected,
                                               OcCurrentOccupancySummary occupancySummary,
                                               boolean randomOutcomeRefreshed) {
        OcTimelineSafetyAssessment assessment = safety.assessment();
        Set<OcRiskFlagEnum> riskFlags = new LinkedHashSet<>(assessment.riskFlags());
        Set<OcPlanReasonCodeEnum> reasonCodes = new LinkedHashSet<>(assessment.reasonCodes());
        OcRefreshVector vector = selected.map(SafeCandidate::vector)
                .orElse(new OcRefreshVector(0, 0));
        OcReplanWindow replanWindow = replanWindow(snapshot, context, assessment);
        if (randomOutcomeRefreshed) {
            reasonCodes.add(OcPlanReasonCodeEnum.RANDOM_OUTCOME_CHANGED);
            replanWindow = replanWindowCalculator.immediateReplan(snapshot.snapshotTime());
        }
        LocalDateTime nextCriticalReleaseAt = assessment.nextCriticalReleaseAt();
        boolean pauseAllowed = OcTimelinePolicy.allowsNewPause(mode);
        boolean pauseSelected = selected.map(candidate ->
                candidate.pauseTier() != SafeCandidate.PauseTier.ZERO_PAUSE).orElse(false);
        if (pauseSelected) {
            riskFlags.add(OcRiskFlagEnum.RECOVERABLE_PAUSE_PRESENT);
        }
        OcValueEvidence.Level evidenceLevel = selected.map(SafeCandidate::valueEvidenceLevel)
                .orElse(OcValueEvidence.Level.INSUFFICIENT);
        if (modeSelector.economicEvidenceInsufficient(safety.candidates(),
                selected.orElse(null))) {
            markEconomicEvidenceInsufficient(riskFlags, reasonCodes);
        }
        List<String> warnings = collectWarnings(snapshot, context, safety);
        Duration selectedPauseDuration = selected.map(candidate ->
                candidate.timelineValue() == null ? Duration.ZERO
                        : candidate.timelineValue().actualNewPause()).orElse(null);
        OcRefreshInstructionPlan plan = new OcRefreshInstructionPlan(snapshot.factionId(),
                snapshot.snapshotTime(), mode, context.plannedEmptyOcCounts(),
                vector.normalCount(), vector.highCount(), safety.lowerBound(),
                reason(vector, context, assessment), context.configurationStatus(),
                assessment.proofStatus(), riskFlags, reasonCodes, nextCriticalReleaseAt,
                pauseAllowed, pauseSelected, pauseSelected ? selectedPauseDuration : null,
                replanWindow, evidenceLevel, occupancySummary, warnings);
        logShadow(plan, safety);
        return plan;
    }

    /**
     * 标记收益证据不足：仅作为匿名事实提示，不得据此提高刷新或停转建议。
     *
     * @param riskFlags   风险标记输出集合
     * @param reasonCodes 原因码输出集合
     */
    private void markEconomicEvidenceInsufficient(Set<OcRiskFlagEnum> riskFlags,
                                                  Set<OcPlanReasonCodeEnum> reasonCodes) {
        riskFlags.add(OcRiskFlagEnum.ECONOMIC_EVIDENCE_INSUFFICIENT);
        reasonCodes.add(OcPlanReasonCodeEnum.ECONOMIC_EVIDENCE_INSUFFICIENT);
    }

    /**
     * 计算当前建议的重新评估窗口。
     *
     * @param snapshot   规划快照
     * @param context    刷新规划上下文
     * @param assessment 时间线安全评估
     * @return 重新评估窗口
     */
    private OcReplanWindow replanWindow(OcPlanningSnapshot snapshot,
                                        OcRefreshPlanningContext context,
                                        OcTimelineSafetyAssessment assessment) {
        List<LocalDateTime> events = new ArrayList<>();
        List<LocalDateTime> boundaries = new ArrayList<>();
        if (assessment.nextCriticalReleaseAt() != null) {
            events.add(assessment.nextCriticalReleaseAt());
        }
        context.request().obligations().forEach(obligation -> {
            if (obligation.firstJoinDeadline() != null) {
                boundaries.add(obligation.firstJoinDeadline());
            }
            LocalDateTime readyAt = obligation.demand().readyAt();
            if (readyAt != null && readyAt.isAfter(snapshot.snapshotTime())) {
                boundaries.add(readyAt);
                events.add(readyAt);
            }
        });
        if (assessment.proofWindowEnd() != null) {
            boundaries.add(assessment.proofWindowEnd().plus(OcTimelinePolicy.REPLAN_LEAD));
        }
        return replanWindowCalculator.calculate(snapshot.snapshotTime(), events, boundaries);
    }

    /**
     * 合并快照、策略、上下文和求解警告。
     *
     * @param snapshot 规划快照
     * @param context  刷新规划上下文
     * @param safety   时间线求解结果
     * @return 合并后的警告
     */
    private List<String> collectWarnings(OcPlanningSnapshot snapshot,
                                         OcRefreshPlanningContext context,
                                         OcRefreshSafetyResult safety) {
        List<String> warnings = new ArrayList<>(snapshot.warnings());
        warnings.addAll(snapshot.policy().validationWarnings());
        warnings.addAll(context.warnings());
        warnings.addAll(safety.warnings());
        return warnings;
    }

    /**
     * 根据配置状态、证明状态和已选向量生成用户可读原因。
     *
     * @param selected   已选刷新向量
     * @param context    刷新规划上下文
     * @param assessment 时间线安全评估
     * @return 刷新建议原因
     */
    private String reason(OcRefreshVector selected, OcRefreshPlanningContext context,
                          OcTimelineSafetyAssessment assessment) {
        if (context.configurationStatus() != OcConfigurationStatusEnum.VALID) {
            return "规划配置存在错误，已停止自动刷新建议";
        }
        if (assessment.reasonCodes().contains(
                OcPlanReasonCodeEnum.PROOF_WINDOW_EXPIRED_FOR_NEW_REFRESH)) {
            return "已进入操作提前区间，暂不新增刷新；等待或确认边界事实后重新运行";
        }
        if (assessment.riskFlags().contains(OcRiskFlagEnum.DEADLOCK_RISK)) {
            return "当前存在全帮卡死或被迫拆队风险，两个刷新池建议均为0";
        }
        if (assessment.riskFlags().contains(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK)) {
            return "已启动链义务存在无法履约风险，已阻断全部新增刷新";
        }
        if (selected.totalCount() > 0) {
            return "已按当前OC占用、达标成员和成员释放时间线证明建议次数可承接";
        }
        if (assessment.proofStatus() == OcProofStatusEnum.UNPROVEN_HEURISTIC_MISS) {
            return "当前预算内未证明可安全承接新的完整阵容";
        }
        if (context.request().normalTemplates().isEmpty()
                && context.request().highChains().isEmpty()) {
            return "当前没有有效的计划刷新池配置";
        }
        return "当前剩余达标成员无法证明可安全承接新的完整阵容";
    }

    /**
     * 输出匿名结构化Shadow日志，不记录成员、岗位、内部排程或奖励明细。
     * 搜索遥测按一次规划汇总，不逐分支输出。
     *
     * @param plan   已生成的刷新指令
     * @param safety 时间线求解结果，提供耗时与搜索遥测
     */
    private void logShadow(OcRefreshInstructionPlan plan, OcRefreshSafetyResult safety) {
        OcSearchTelemetry telemetry = safety.searchTelemetry();
        log.info("OC新队Shadow: factionId={}, mode={}, snapshotTime={}, configurationStatus={}, "
                        + "proofStatus={}, riskFlags={}, lowerBound={}, selectedVector=({},{}), "
                        + "nextCriticalReleaseAt={}, nextReplanAt={}, latestReplanAt={}, "
                        + "pauseAllowed={}, pauseSelected={}, pendingEmptyCount={}, "
                        + "reasonCodes={}, warningCount={}, solveElapsedMillis={}, "
                        + "combinationEvaluations={}, budgetTruncations={}, "
                        + "alternativesCapHits={}",
                plan.factionId(), plan.mode(), plan.snapshotTime(),
                plan.configurationStatus(), plan.proofStatus(), plan.riskFlags(),
                plan.lowerBound(), plan.normalRefreshCount(), plan.highRefreshCount(),
                plan.nextCriticalReleaseAt(), plan.replanWindow().nextReplanAt(),
                plan.replanWindow().latestReplanAt(), plan.pauseAllowed(),
                plan.pauseSelected(), plan.plannedEmptyOcCounts().values().stream()
                        .mapToInt(Integer::intValue).sum(),
                plan.reasonCodes(), plan.warnings().size(), safety.elapsedMillis(),
                telemetry.combinationEvaluations(), telemetry.budgetTruncations(),
                telemetry.alternativesCapHits());
    }
}
