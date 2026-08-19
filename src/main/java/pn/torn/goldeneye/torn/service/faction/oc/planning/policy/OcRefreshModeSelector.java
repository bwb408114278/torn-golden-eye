package pn.torn.goldeneye.torn.service.faction.oc.planning.policy;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.service.faction.oc.planning.evidence.OcEconomicValueComparator;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 从同一批已证明安全且已评分的候选向量中按模式选择刷新指令。
 *
 * <p>不再使用25/50/100容量比例缩放；模式差异来自停转容忍、流动性余量和价值目标。
 * 已证明至少存在一个安全刷新向量时，不会因旧百分比取整返回0。
 * 收益模式的资格判定需要完整候选集合：收益级停转须严格优于集合内的
 * 零新增停转替代时间线，PRIOR_ONLY先验须相对替代候选可稳定区分。</p>
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.07.17
 */
@Component
public class OcRefreshModeSelector {
    private final OcEconomicValueComparator valueComparator = new OcEconomicValueComparator();

    /**
     * 选择指定模式的安全刷新向量。
     *
     * @param safety 时间线求解结果
     * @param mode   刷新策略模式
     * @return 满足模式停转政策的最优已证明安全向量；无安全候选时为零向量
     */
    public OcRefreshVector select(OcRefreshSafetyResult safety, OcPlanMode mode) {
        return selectCandidate(safety, mode).map(SafeCandidate::vector)
                .orElse(new OcRefreshVector(0, 0));
    }

    /**
     * 选择指定模式的安全候选，含停转层级和评分信息。
     *
     * @param safety 时间线求解结果
     * @param mode   刷新策略模式
     * @return 满足模式停转政策的最优候选；无安全候选时为空
     */
    public Optional<SafeCandidate> selectCandidate(OcRefreshSafetyResult safety,
                                                   OcPlanMode mode) {
        if (isHardBlocked(safety)) {
            return Optional.empty();
        }
        List<SafeCandidate> candidates = safety.candidates();
        SafeCandidate zeroPauseBaseline = safety.zeroPauseBaseline();
        List<SafeCandidate> eligible = candidates.stream()
                .filter(candidate -> withinPausePolicy(candidate, mode, candidates,
                        zeroPauseBaseline, safety.baselineComparable())).toList();
        return eligible.stream().max(comparator(mode));
    }

    /**
     * 判断求解结果是否被硬性阻断：配置无效、已证明不可行、卡死或硬义务风险。
     *
     * @param safety 时间线求解结果
     * @return 存在硬阻断时返回true
     */
    private boolean isHardBlocked(OcRefreshSafetyResult safety) {
        return safety.assessment().riskFlags().contains(OcRiskFlagEnum.DEADLOCK_RISK)
                || safety.assessment().riskFlags().contains(OcRiskFlagEnum.HARD_OBLIGATION_AT_RISK)
                || safety.assessment().proofStatus() == OcProofStatusEnum.PROVEN_INFEASIBLE;
    }

    /**
     * 判断候选是否满足指定模式的选择前提。
     *
     * <p>保守按零停转层级放宽；均衡级主动新增停转必须在阶段二证明相对零停转基准
     * "价值严格提高或完整释放严格提前"准入后才可参与均衡排序，过滤先于排序发生；
     * 收益模式的正向量必须具备可用的完整价值证据，金额证据不足的候选仅用于匿名说明，
     * 不得提高刷新建议。</p>
     *
     * @param candidate          安全候选
     * @param mode               刷新策略模式
     * @param candidates         本次求解的完整安全候选集合
     * @param zeroPauseBaseline  候选集合中的最优零停转替代候选；不存在时为null
     * @param baselineComparable 全局零停转基准是否具备收益比较条件
     * @return 满足政策时返回true
     */
    private boolean withinPausePolicy(SafeCandidate candidate, OcPlanMode mode,
                                      List<SafeCandidate> candidates,
                                      SafeCandidate zeroPauseBaseline,
                                      boolean baselineComparable) {
        return switch (mode) {
            case CONSERVATIVE -> candidate.pauseTier() == SafeCandidate.PauseTier.ZERO_PAUSE;
            case BALANCED -> candidate.pauseTier() == SafeCandidate.PauseTier.ZERO_PAUSE
                    || (candidate.pauseTier() == SafeCandidate.PauseTier.WITHIN_BALANCED
                    && candidate.balancedPauseCandidateEligible());
            case PROFIT -> withinProfitPolicy(candidate, candidates, zeroPauseBaseline,
                    baselineComparable);
        };
    }

    /**
     * 判断候选是否满足收益模式选点前提。
     *
     * <p>零向量不受价值证据限制；正向量要求证据层级可用。收益级停转必须在
     * 组合评估中证明严格优于零停转基准，且在最终候选集合中仍严格优于当前
     * 最优零新增停转替代时间线，防止搜索顺序缺口放宽门禁；基准不存在或
     * 不可比较时fail-closed。零停转或均衡级停转候选在PRIOR_ONLY先验
     * 可区分时也允许参与收益模式选点。</p>
     *
     * @param candidate          安全候选
     * @param candidates         本次求解的完整安全候选集合
     * @param zeroPauseBaseline  候选集合中的最优零停转替代候选；不存在时为null
     * @param baselineComparable 全局零停转基准是否具备收益比较条件
     * @return 满足收益模式前提时返回true
     */
    private boolean withinProfitPolicy(SafeCandidate candidate, List<SafeCandidate> candidates,
                                       SafeCandidate zeroPauseBaseline,
                                       boolean baselineComparable) {
        if (candidate.vector().totalCount() == 0) {
            return true;
        }
        if (!hasCompleteValueEvidence(candidate)) {
            return false;
        }
        if (candidate.pauseTier() == SafeCandidate.PauseTier.WITHIN_PROFIT) {
            return baselineComparable && zeroPauseBaseline != null
                    && hasCompleteValueEvidence(zeroPauseBaseline)
                    && candidate.zeroPauseBaselineComparable()
                    && candidate.pauseCandidateStrictlyBetterThanBaseline();
        }
        return hasComparableEvidence(candidate, candidates);
    }

    /**
     * 判断正向候选是否具备与调用模式一致的已证明资格。
     *
     * <p>收益模式下沿用收益级完整门禁；均衡模式下{@code WITHIN_BALANCED}正向候选
     * 只有在阶段二均衡准入为真时才计为已证明正向停转候选，其余停转层级不得
     * 借均衡模式提高建议。零停转正向候选的证据判断与模式无关，保持既有逻辑。</p>
     *
     * @param candidate          待判断的正向候选
     * @param mode               调用方的刷新策略模式
     * @param candidates         当前求解结果中的全部候选
     * @param zeroPauseBaseline  阶段二确定的全局零停转基准
     * @param baselineComparable 全局零停转基准是否具备收益比较条件
     * @return 证据完整且满足当前候选停转层级在指定模式下的资格时返回true
     */
    public boolean isPositiveCandidateProven(SafeCandidate candidate,
                                             OcPlanMode mode,
                                             List<SafeCandidate> candidates,
                                             SafeCandidate zeroPauseBaseline,
                                             boolean baselineComparable) {
        if (candidate == null || candidate.vector().totalCount() <= 0
                || !hasCompleteValueEvidence(candidate)) {
            return false;
        }
        if (candidate.pauseTier() == SafeCandidate.PauseTier.WITHIN_PROFIT) {
            return baselineComparable && zeroPauseBaseline != null
                    && hasCompleteValueEvidence(zeroPauseBaseline)
                    && candidate.zeroPauseBaselineComparable()
                    && candidate.pauseCandidateStrictlyBetterThanBaseline();
        }
        if (candidate.pauseTier() == SafeCandidate.PauseTier.WITHIN_BALANCED
                && mode == OcPlanMode.BALANCED) {
            return candidate.balancedPauseCandidateEligible();
        }
        return hasComparableEvidence(candidate, candidates);
    }

    /**
     * 判断正候选证据是否可比较：金额层级必须携带金额；PRIOR_ONLY必须在候选集合
     * 中可稳定区分，即严格优于至少一个其他正向量；INSUFFICIENT不得用于提高建议。
     *
     * @param candidate  安全候选
     * @param candidates 本次求解的完整安全候选集合
     * @return 证据可比较时返回true
     */
    private boolean hasComparableEvidence(SafeCandidate candidate,
                                          List<SafeCandidate> candidates) {
        return switch (candidate.valueEvidenceLevel()) {
            case OBSERVED_REWARD, REWARD_FLOOR -> candidate.timelineValue().monetaryValue() != null;
            case PRIOR_ONLY -> distinguishableAgainstAlternative(candidate, candidates);
            case INSUFFICIENT -> false;
        };
    }

    /**
     * 判断PRIOR_ONLY候选的先验元组是否在候选集合层面可稳定区分：
     * 至少严格优于一个其他正向量。完全相同或无替代比较对象时不可区分，
     * 不得据此提高收益建议。
     *
     * @param candidate  安全候选
     * @param candidates 本次求解的完整安全候选集合
     * @return 存在被候选严格超越的替代正向量时返回true
     */
    private boolean distinguishableAgainstAlternative(SafeCandidate candidate,
                                                      List<SafeCandidate> candidates) {
        return candidates.stream()
                .filter(other -> other != candidate && other.vector().totalCount() > 0)
                .anyMatch(other -> valueComparator.compareTimelineValue(
                        candidate.timelineValue(), other.timelineValue()) < 0);
    }

    /**
     * 判断候选的证据层级是否满足收益模式提高建议的冻结可用等级。
     * PRIOR_ONLY可稳定区分时允许参与收益模式选点。
     *
     * @param candidate 安全候选
     * @return 层级不为INSUFFICIENT时返回true
     */
    private boolean hasUsableEvidenceLevel(SafeCandidate candidate) {
        return candidate.valueEvidenceLevel() != OcValueEvidence.Level.INSUFFICIENT;
    }

    private boolean hasCompleteValueEvidence(SafeCandidate candidate) {
        return candidate != null && hasUsableEvidenceLevel(candidate)
                && candidate.timelineValue() != null
                && candidate.timelineValue().evidenceLevel() != OcValueEvidence.Level.INSUFFICIENT;
    }

    /**
     * 判断收益选点是否存在经济证据不足事实：选中正向量证据层级不可用或PRIOR_ONLY
     * 先验关键字段缺失；或因PRIOR_ONLY不可区分回落零向量。该判定只输出匿名事实，
     * 供最终计划提示经济证据不足，不改变选择结果本身。
     *
     * @param candidates 本次求解的完整安全候选集合
     * @param selected   已选安全候选；未选中时为null
     * @return 存在经济证据不足事实时返回true
     */
    public boolean economicEvidenceInsufficient(List<SafeCandidate> candidates,
                                                SafeCandidate selected) {
        List<SafeCandidate> positive = candidates.stream()
                .filter(candidate -> candidate.vector().totalCount() > 0).toList();
        if (positive.isEmpty()) {
            return false;
        }
        if (selected != null && selected.vector().totalCount() > 0) {
            return insufficientForSelected(selected);
        }
        return positive.stream().allMatch(candidate ->
                candidate.timelineValue() == null
                        || candidate.timelineValue().monetaryValue() == null);
    }

    /**
     * 判断已选中正向量的证据是否可用：层级不可用或PRIOR_ONLY先验关键字段缺失。
     *
     * @param selected 已选安全候选
     * @return 证据不足时返回true
     */
    private boolean insufficientForSelected(SafeCandidate selected) {
        if (selected.valueEvidenceLevel() == OcValueEvidence.Level.INSUFFICIENT
                || selected.timelineValue() == null) {
            return true;
        }
        return selected.valueEvidenceLevel() == OcValueEvidence.Level.PRIOR_ONLY
                && (selected.timelineValue().highestRank() <= 0
                || selected.timelineValue().totalRequiredMembers() <= 0
                || selected.timelineValue().chainNodeCount() <= 0);
    }

    /**
     * 构造指定模式的候选排序器，返回正数表示左候选更优。
     *
     * @param mode 刷新策略模式
     * @return 模式对应的候选排序器
     */
    private Comparator<SafeCandidate> comparator(OcPlanMode mode) {
        return switch (mode) {
            case CONSERVATIVE -> conservativeComparator();
            case BALANCED -> balancedComparator();
            case PROFIT -> profitComparator();
        };
    }

    /**
     * 保守模式：更大已证明联合向量 → 更强流动性余量 → 普通池优先 → 稳定tie-break。
     *
     * @return 保守模式排序器
     */
    private Comparator<SafeCandidate> conservativeComparator() {
        return (left, right) -> {
            int total = Integer.compare(left.vector().totalCount(),
                    right.vector().totalCount());
            if (total != 0) {
                return total;
            }
            int anchors = Integer.compare(left.anchorCount(), right.anchorCount());
            if (anchors != 0) {
                return anchors;
            }
            int normal = Integer.compare(left.vector().normalCount(),
                    right.vector().normalCount());
            return normal != 0 ? normal
                    : Integer.compare(left.vector().highCount(), right.vector().highCount());
        };
    }

    /**
     * 均衡模式：更大已证明联合向量 → 更连续释放 → 普通高阶平衡 → 稳定tie-break。
     *
     * @return 均衡模式排序器
     */
    private Comparator<SafeCandidate> balancedComparator() {
        return (left, right) -> {
            int total = Integer.compare(left.vector().totalCount(),
                    right.vector().totalCount());
            if (total != 0) {
                return total;
            }
            int release = compareEarlierBetter(left.guaranteedEarliestReleaseAt(),
                    right.guaranteedEarliestReleaseAt());
            if (release != 0) {
                return release;
            }
            int anchors = Integer.compare(left.anchorCount(), right.anchorCount());
            if (anchors != 0) {
                return anchors;
            }
            int balance = Integer.compare(
                    Math.abs(right.vector().normalCount() - right.vector().highCount()),
                    Math.abs(left.vector().normalCount() - left.vector().highCount()));
            if (balance != 0) {
                return balance;
            }
            int normal = Integer.compare(left.vector().normalCount(),
                    right.vector().normalCount());
            return normal != 0 ? normal
                    : Integer.compare(left.vector().highCount(), right.vector().highCount());
        };
    }

    /**
     * 收益模式：规划窗口全局总价值 → 增量单位成员人天 → 更早释放 → 刷新总数 → 稳定tie-break。
     *
     * @return 收益模式排序器
     */
    private Comparator<SafeCandidate> profitComparator() {
        return (left, right) -> {
            int valueResult = -valueComparator.compareTimelineValue(
                    left.timelineValue(), right.timelineValue());
            if (valueResult != 0) {
                return valueResult;
            }
            int total = Integer.compare(left.vector().totalCount(),
                    right.vector().totalCount());
            if (total != 0) {
                return total;
            }
            return Integer.compare(left.vector().normalCount(),
                    right.vector().normalCount());
        };
    }

    /**
     * 比较两个释放时间，更早者更优。
     *
     * @param left  左候选释放时间
     * @param right 右候选释放时间
     * @return 左候选更早时返回正数；任一缺失时返回0
     */
    private int compareEarlierBetter(LocalDateTime left, LocalDateTime right) {
        if (left == null || right == null) {
            return 0;
        }
        return right.compareTo(left);
    }
}
