package pn.torn.goldeneye.torn.service.faction.oc.planning.timeline;

import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.service.faction.oc.planning.matching.OcPreparationTimeCalculator;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 时间线真实价值摘要累积器。只从已完成模拟的状态事实计算实际人天、实际停转、
 * 保证释放、可避免过期和既有义务完成延迟，不在该层读取数据库。
 * 金额与业务先验由搜索层用静态 {@link OcValueEvidence}合并，本层不猜测模板奖励。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public final class OcTimelineValueAccumulator {

    /**
     * 从时间线状态累积真实价值事实。
     *
     * @param state               已完成模拟的时间线状态
     * @param plannedEmptyExpired 模拟中是否出现过被跳过的计划内无人OC
     * @param request             本次模拟对应的求解请求，用于读取既有义务的基准完成事实
     * @return 真实时间线价值摘要（金额字段由搜索层补充）
     */
    public OcTimelineValueSummary accumulate(OcTimelineState state,
                                             boolean plannedEmptyExpired,
                                             OcRefreshSafetyRequest request) {
        return new OcTimelineValueSummary(null,
                actualIncrementalMemberDays(state),
                maxNewPause(state),
                existingObligationDelay(state, request),
                !plannedEmptyExpired,
                earliestCompletionRelease(state.events()),
                0, 0, 1, OcValueEvidence.Level.INSUFFICIENT);
    }

    /**
     * 计算实际增量剩余成员人天：仅排除快照前既有OC的固定成员区间，
     * 其余来源均按真实占用区间计入。
     *
     * @param state 时间线状态
     * @return 按24小时折算的实际增量成员人天
     */
    private int actualIncrementalMemberDays(OcTimelineState state) {
        long totalMinutes = 0;
        for (OcMemberInterval interval : state.intervals()) {
            if (!countsAsIncrementalMemberDay(interval)) {
                continue;
            }
            totalMinutes += Duration.between(interval.occupiedFrom(),
                    interval.occupiedUntil()).toMinutes();
        }
        return (int) ((totalMinutes + 1439) / 1440);
    }

    /**
     * 判断成员占用区间是否属于本次规划的新增资源占用。
     *
     * @param interval 成员占用区间
     * @return 应计入增量成员人天时返回true
     */
    private boolean countsAsIncrementalMemberDay(OcMemberInterval interval) {
        return interval.source() != OcMemberInterval.IntervalSource.EXISTING_OC;
    }

    /**
     * 获取时间线实际单次最大主动新增停转时长。
     *
     * @param state 时间线状态
     * @return 最大新增停转时长；无主动新增停转时为零
     */
    private Duration maxNewPause(OcTimelineState state) {
        return state.pauses().stream()
                .filter(pause -> !pause.preExistingPause())
                .map(OcPauseAssessment::newPauseDuration)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
    }

    /**
     * 计算既有现实义务相对其无主动停转进度的完成延迟。
     *
     * <p>只把快照中的已有人OC和已启动链后继（含链推进后生成的后继）纳入延迟；
     * 计划内无人OC与条件随机根不计入，避免把候选自身成本重复算为既有义务延迟。
     * 已启动链后继虽然自身不允许主动新增停转，但其生成时刻由前置节点完成时间决定，
     * 前置发生收益级主动停转时后继完成也会整体后移，因此链后继同样按
     * “前置完成/生成时间 + 剩余完整岗位理想准备时间”计算延迟。
     * 任一既有义务缺少可证明的实际完成时间或基准完成时间时，返回不可比较哨兵，
     * 由上层收益比较 fail-closed，不得填伪零值。</p>
     *
     * @param state   时间线状态
     * @param request 求解请求
     * @return 全部既有义务中的最大完成延迟；基准不可证明时返回
     * {@link OcTimelineValueSummary#UNPROVEN_OBLIGATION_DELAY}
     */
    private Duration existingObligationDelay(OcTimelineState state,
                                             OcRefreshSafetyRequest request) {
        Map<String, LocalDateTime> actualCompletionByKey = state.anchors().stream()
                .collect(Collectors.toMap(OcLiquidityAnchor::anchorKey,
                        OcLiquidityAnchor::releaseAt, (left, right) -> right));
        Map<String, LocalDateTime> chainGeneratedAtByKey = state.events().stream()
                .filter(event -> event.type() == OcTimelineEvent.EventType.CHAIN_SUCCESSOR_GENERATED)
                .collect(Collectors.toMap(OcTimelineEvent::obligationKey,
                        OcTimelineEvent::eventTime, (left, right) -> right));
        Set<String> committedChainKeys = collectCommittedChainKeys(state, request);
        Map<String, OcTimelineObligation> requestObligationByKey = request.obligations().stream()
                .collect(Collectors.toMap(OcTimelineObligation::key, obligation -> obligation,
                        (left, right) -> right));

        Duration requestDelay = requestObligationWorstDelay(request, actualCompletionByKey,
                state.snapshotTime());
        if (requestDelay == null) {
            return OcTimelineValueSummary.UNPROVEN_OBLIGATION_DELAY;
        }
        Duration chainDelay = dynamicChainWorstDelay(committedChainKeys,
                requestObligationByKey, state, actualCompletionByKey, chainGeneratedAtByKey);
        if (chainDelay == null) {
            return OcTimelineValueSummary.UNPROVEN_OBLIGATION_DELAY;
        }
        return maxDelay(requestDelay, chainDelay);
    }

    private Set<String> collectCommittedChainKeys(OcTimelineState state,
                                                  OcRefreshSafetyRequest request) {
        Set<String> committedChainKeys = new HashSet<>(state.chainSuccessorDemandKeys());
        request.obligations().stream()
                .filter(obligation -> obligation.kind()
                        == OcTimelineObligation.ObligationKind.COMMITTED_CHAIN_SUCCESSOR)
                .map(OcTimelineObligation::key)
                .forEach(committedChainKeys::add);
        state.events().stream()
                .filter(event -> event.type() == OcTimelineEvent.EventType.CHAIN_SUCCESSOR_GENERATED)
                .map(OcTimelineEvent::obligationKey)
                .forEach(committedChainKeys::add);
        return committedChainKeys;
    }

    private Duration requestObligationWorstDelay(OcRefreshSafetyRequest request,
                                                 Map<String, LocalDateTime> actualCompletionByKey,
                                                 LocalDateTime snapshotTime) {
        Duration worst = Duration.ZERO;
        for (OcTimelineObligation obligation : request.obligations()) {
            if (!obligation.isHardObligation()) {
                continue;
            }
            LocalDateTime actual = actualCompletionByKey.get(obligation.key());
            if (actual == null) {
                return null;
            }
            Duration delay = hardObligationDelay(obligation, actual, snapshotTime);
            if (delay == null) {
                return null;
            }
            worst = maxDelay(worst, delay);
        }
        return worst;
    }

    private Duration dynamicChainWorstDelay(Set<String> committedChainKeys,
                                            Map<String, OcTimelineObligation> requestObligationByKey,
                                            OcTimelineState state,
                                            Map<String, LocalDateTime> actualCompletionByKey,
                                            Map<String, LocalDateTime> chainGeneratedAtByKey) {
        Duration worst = Duration.ZERO;
        for (String chainKey : committedChainKeys) {
            if (requestObligationByKey.containsKey(chainKey)) {
                continue;
            }
            LocalDateTime actual = actualCompletionByKey.get(chainKey);
            if (actual == null) {
                return null;
            }
            Duration delay = chainSuccessorDelay(state.chainSuccessorDemand(chainKey),
                    actual, chainGeneratedAtByKey.get(chainKey));
            if (delay == null) {
                return null;
            }
            worst = maxDelay(worst, delay);
        }
        return worst;
    }

    private Duration maxDelay(Duration current, Duration candidate) {
        return candidate.compareTo(current) > 0 ? candidate : current;
    }

    /**
     * 计算单个既有义务的完成延迟；已有人OC按其当前阶段时间计算，
     * 已启动链后继按其前置完成/生成时间计算。
     *
     * @param obligation   既有义务
     * @param actual       实际完成释放时间
     * @param snapshotTime 快照时间
     * @return 完成延迟；基准无法证明时返回null
     */
    private Duration hardObligationDelay(OcTimelineObligation obligation,
                                         LocalDateTime actual,
                                         LocalDateTime snapshotTime) {
        if (obligation.kind() == OcTimelineObligation.ObligationKind.COMMITTED_CHAIN_SUCCESSOR) {
            return chainSuccessorDelay(obligation.demand(), actual,
                    obligation.predecessorCompletedAt());
        }
        if (obligation.kind() != OcTimelineObligation.ObligationKind.EXISTING_JOINED) {
            return null;
        }
        OcTeamDemand demand = obligation.demand();
        if (demand.readyAt() == null || demand.slots().isEmpty()) {
            return null;
        }
        LocalDateTime baselineReadyAt = demand.readyAt().isBefore(snapshotTime)
                ? snapshotTime : demand.readyAt();
        LocalDateTime baseline = OcPreparationTimeCalculator.idealCompletionTime(
                baselineReadyAt, demand.slots().size(), demand.fixedMemberIds().size());
        return actual.isBefore(baseline) ? Duration.ZERO : Duration.between(baseline, actual);
    }

    /**
     * 计算链后继相对无主动停转进度的完成延迟。
     *
     * <p>基准起点为前置实际完成/生成时间；此后按后继完整岗位需求与已加入成员
     * 事实递推理想完成时间。缺少前置时间、岗位需求或实际完成释放时返回null，
     * 由调用方输出不可证明哨兵。</p>
     *
     * @param demand                 链后继岗位需求
     * @param actual                 实际完成释放时间
     * @param predecessorCompletedAt 前置实际完成或生成时间
     * @return 完成延迟；基准无法证明时返回null
     */
    private Duration chainSuccessorDelay(OcTeamDemand demand,
                                         LocalDateTime actual,
                                         LocalDateTime predecessorCompletedAt) {
        if (demand == null || demand.slots().isEmpty()
                || predecessorCompletedAt == null) {
            return null;
        }
        LocalDateTime baseline = OcPreparationTimeCalculator.idealCompletionTime(
                predecessorCompletedAt, demand.slots().size(),
                demand.fixedMemberIds().size());
        return actual.isBefore(baseline) ? Duration.ZERO : Duration.between(baseline, actual);
    }

    /**
     * 从完成释放事件中获取最早完整释放时间。
     *
     * @param events 时间线事件
     * @return 最早完成释放时间；无完成事件时为null
     */
    private LocalDateTime earliestCompletionRelease(List<OcTimelineEvent> events) {
        return events.stream()
                .filter(event -> event.type() == OcTimelineEvent.EventType.COMPLETION_RELEASE)
                .map(OcTimelineEvent::eventTime)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }
}
