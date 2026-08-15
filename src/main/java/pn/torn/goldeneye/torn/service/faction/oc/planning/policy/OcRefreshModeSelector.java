package pn.torn.goldeneye.torn.service.faction.oc.planning.policy;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.*;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import pn.torn.goldeneye.torn.service.faction.oc.planning.evidence.OcEconomicValueComparator;

/**
 * 从同一批已证明安全且已评分的候选向量中按模式选择刷新指令。
 *
 * <p>不再使用25/50/100容量比例缩放；模式差异来自停转容忍、流动性余量和价值目标。
 * 已证明至少存在一个安全刷新向量时，不会因旧百分比取整返回0。</p>
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
        List<SafeCandidate> eligible = safety.candidates().stream()
                .filter(candidate -> withinPausePolicy(candidate, mode)).toList();
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
     * <p>保守和均衡按停转层级放宽；收益模式的正向量必须具备可用的完整价值证据，
     * 金额证据不足的候选仅用于匿名说明，不得提高刷新建议。</p>
     *
     * @param candidate 安全候选
     * @param mode      刷新策略模式
     * @return 满足政策时返回true
     */
    private boolean withinPausePolicy(SafeCandidate candidate, OcPlanMode mode) {
        return switch (mode) {
            case CONSERVATIVE -> candidate.pauseTier() == SafeCandidate.PauseTier.ZERO_PAUSE;
            case BALANCED -> candidate.pauseTier() != SafeCandidate.PauseTier.WITHIN_PROFIT;
            case PROFIT -> candidate.vector().totalCount() == 0
                    || candidate.usableForAdviceIncrease()
                    && candidate.windowValue() != null
                    && hasUsableEvidenceLevel(candidate);
        };
    }

    /**
     * 判断候选的证据层级是否满足收益模式提高建议的冻结可用等级。
     *
     * @param candidate 安全候选
     * @return 层级不低于收益下界证据时返回true
     */
    private boolean hasUsableEvidenceLevel(SafeCandidate candidate) {
        return OcValueEvidence.Level.REWARD_FLOOR.compareTo(candidate.valueEvidenceLevel()) >= 0;
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
            int release = compareEarlierBetter(left.earliestCompletionAt(),
                    right.earliestCompletionAt());
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
            int valueResult = -valueComparator.compare(left.windowValue(),
                    left.incrementalMemberDays(), left.earliestCompletionAt(),
                    right.windowValue(), right.incrementalMemberDays(),
                    right.earliestCompletionAt());
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
