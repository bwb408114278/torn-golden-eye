package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import lombok.extern.slf4j.Slf4j;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcLiquidityAnchor;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPauseAssessment;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyRequest;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineBranchExpander.SearchBranch;
import pn.torn.goldeneye.torn.service.faction.oc.planning.timeline.OcTimelineEventScheduler.SimulationResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 时间线模拟结果装配协作类。负责预算、失败、锚点结果的装配与
 * 搜索进度累积，由时间线事件推进器显式构造。纯内存对象，不访问数据库、HTTP或Redis。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Slf4j
final class OcTimelineSimulationResultFactory {
    private final OcLiquidityPathVerifier liquidityVerifier;

    /**
     * 创建时间线模拟结果装配器。
     *
     * @param liquidityVerifier 流动性路径验证器
     */
    OcTimelineSimulationResultFactory(OcLiquidityPathVerifier liquidityVerifier) {
        this.liquidityVerifier = liquidityVerifier;
    }

    /**
     * 一次模拟的搜索进度与失败语义累积状态。字段由同包的时间线事件推进器
     * 及其协作类在单次模拟内读写，不对外暴露。
     */
    static final class SearchProgress {
        final OcRefreshSafetyRequest request;
        int expansions;
        boolean deterministicFailure;
        boolean hardObligationFailed;
        boolean plannedEmptyExpired;
        boolean liquidityPathBroken;
        boolean matchAlternativesCapped;
        boolean stateCapTruncated;
        OcTimelineState representativeState;

        private SearchProgress(OcRefreshSafetyRequest request) {
            this.request = request;
            this.representativeState = new OcTimelineState(request);
        }
    }

    /**
     * 创建一次模拟的搜索进度。
     *
     * @param request 求解请求
     * @return 搜索进度
     */
    SearchProgress newProgress(OcRefreshSafetyRequest request) {
        return new SearchProgress(request);
    }

    /**
     * 装配一次模拟的最终结果：可行分支经锚点替换验证并携带连续性证明，
     * 不可行时输出累积失败语义。预算截断语义由调用方映射，不在此处改写。
     *
     * @param complete        可行完成分支；不可行时为null
     * @param progress        搜索进度与失败标记
     * @param budgetExhausted 搜索是否因预算截断
     * @param proofWindowEnd  有限证明窗口结束时间
     * @return 时间线模拟结果
     */
    SimulationResult assembleResult(SearchBranch complete, SearchProgress progress,
                                    boolean budgetExhausted,
                                    LocalDateTime proofWindowEnd) {
        logBudgetShadow(progress, budgetExhausted);
        if (complete == null) {
            OcTimelineState state = progress.representativeState;
            return new SimulationResult(false, progress.deterministicFailure,
                    progress.hardObligationFailed, progress.plannedEmptyExpired,
                    representativeProof(state, progress, proofWindowEnd),
                    state.pauses(), state.events(), maxNewPause(state),
                    budgetExhausted);
        }
        OcTimelineState state = complete.state();
        List<OcLiquidityAnchor> verified = liquidityVerifier.verifyReplacementAnchors(
                state.anchors(), state.intervals());
        return new SimulationResult(true, false, false, complete.plannedEmptyExpired(),
                new OcTimelineEventScheduler.LiquidityProof(verified, state.intervals(),
                        true),
                state.pauses(), state.events(), maxNewPause(state),
                budgetExhausted);
    }

    /**
     * 输出匿名技术预算Shadow日志，记录本次模拟是否命中搜索预算上限。
     * 不记录成员、岗位、内部排程或奖励明细。
     *
     * @param progress        搜索进度与预算命中标记
     * @param budgetExhausted 搜索是否因义务展开预算截断
     */
    private void logBudgetShadow(SearchProgress progress, boolean budgetExhausted) {
        if (budgetExhausted || progress.matchAlternativesCapped
                || progress.stateCapTruncated) {
            log.info("OC新队Shadow: searchBudget taskExpansionBudgetExhausted={}, "
                            + "stateCapTruncated={}, matchAlternativesCapped={}",
                    budgetExhausted, progress.stateCapTruncated,
                    progress.matchAlternativesCapped);
        }
    }

    /**
     * 构造不可行结果代表的流动性证明：按代表状态的锚点与占用区间回填替换标记，
     * 并在搜索中出现过连续性失败时保持连续路径为假。
     *
     * @param state          代表状态
     * @param progress       搜索进度与失败标记
     * @param proofWindowEnd 有限证明窗口结束时间
     * @return 匿名流动性证明状态
     */
    private OcTimelineEventScheduler.LiquidityProof representativeProof(OcTimelineState state,
                                                                        SearchProgress progress,
                                                                        LocalDateTime proofWindowEnd) {
        boolean continuous = !progress.liquidityPathBroken
                && liquidityVerifier.hasContinuousCompletionPath(state.anchors(),
                state.intervals(), proofWindowEnd);
        return new OcTimelineEventScheduler.LiquidityProof(
                liquidityVerifier.verifyReplacementAnchors(state.anchors(),
                        state.intervals()), state.intervals(), continuous);
    }

    /**
     * 计算当前时间线中最大单次主动新增停转时长。
     *
     * @param state 当前时间线状态
     * @return 最大新增停转时长；无停转时为零
     */
    private Duration maxNewPause(OcTimelineState state) {
        return state.pauses().stream()
                .filter(pause -> !pause.preExistingPause())
                .map(OcPauseAssessment::newPauseDuration)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
    }
}
