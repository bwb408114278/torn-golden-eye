package pn.torn.goldeneye.torn.service.faction.oc.planning.evidence;

import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshSafetyResult.SafeCandidate;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcTimelineValueSummary;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 候选时间线经济价值比较器。只能在硬安全与完整时间线可行之后使用，固定比较顺序：
 * 金额可用的全局值（或第三层业务先验）→ 可避免过期 → 已有人/链延迟 →
 * 实际增量人天 → 保证释放 → 稳定tie-break。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public class OcEconomicValueComparator {

    /**
     * 比较两个已证明安全候选的时间线价值顺序，返回负数表示left更优。
     *
     * @param left  左候选时间线价值摘要
     * @param right 右候选时间线价值摘要
     * @return 左候选更优时返回负数；完全不可区分时返回0
     */
    public int compareTimelineValue(OcTimelineValueSummary left,
                                    OcTimelineValueSummary right) {
        int valueResult = compareMonetaryOrPrior(left, right);
        if (valueResult != 0) {
            return valueResult;
        }
        int expiryResult = Boolean.compare(right.avoidableExpiryPressure(),
                left.avoidableExpiryPressure());
        if (expiryResult != 0) {
            return expiryResult;
        }
        int delayResult = left.existingObligationDelay().compareTo(
                right.existingObligationDelay());
        if (delayResult != 0) {
            return delayResult;
        }
        int memberDayResult = Integer.compare(left.actualIncrementalMemberDays(),
                right.actualIncrementalMemberDays());
        if (memberDayResult != 0) {
            return memberDayResult;
        }
        return compareRelease(left.guaranteedReleaseAt(), right.guaranteedReleaseAt());
    }

    /**
     * 兼容旧测试/旧比较路径：按金额、单位人天和释放时间比较。
     *
     * @param leftValue       左候选窗口总价值
     * @param leftMemberDays  左候选增量人天
     * @param leftReleaseAt   左候选最早释放时间
     * @param rightValue      右候选窗口总价值
     * @param rightMemberDays 右候选增量人天
     * @param rightReleaseAt  右候选最早释放时间
     * @return 左候选更优时返回负数
     */
    public int compare(BigDecimal leftValue, int leftMemberDays,
                       java.time.LocalDateTime leftReleaseAt,
                       BigDecimal rightValue, int rightMemberDays,
                       java.time.LocalDateTime rightReleaseAt) {
        int valueResult = compareValues(leftValue, rightValue);
        if (valueResult != 0) {
            return valueResult;
        }
        if (leftValue != null && rightValue != null
                && leftMemberDays > 0 && rightMemberDays > 0) {
            BigDecimal leftPerDay = leftValue.divide(BigDecimal.valueOf(leftMemberDays),
                    java.math.MathContext.DECIMAL64);
            BigDecimal rightPerDay = rightValue.divide(BigDecimal.valueOf(rightMemberDays),
                    java.math.MathContext.DECIMAL64);
            int perDayResult = rightPerDay.compareTo(leftPerDay);
            if (perDayResult != 0) {
                return perDayResult;
            }
        }
        return compareRelease(leftReleaseAt, rightReleaseAt);
    }

    /**
     * 比较两个金额的全局价值。
     *
     * @param leftValue  左候选价值
     * @param rightValue 右候选价值
     * @return 左候选更优时返回负数；任一金额缺失时返回0
     */
    public int compareValues(BigDecimal leftValue, BigDecimal rightValue) {
        if (leftValue == null || rightValue == null) {
            return 0;
        }
        return rightValue.compareTo(leftValue);
    }

    /**
     * 判断收益级停转候选是否严格优于同组合零新增停转基准。
     * 任一摘要金额、先验或既有义务完成延迟不可比较时返回false，
     * 不得据此提高收益停转建议。既有义务完成延迟是完整净价值的负向事实，
     * 即使名义奖励更高，也不能让延迟更长的收益级停转候选通过严格比较。
     *
     * @param candidate 收益级停转候选摘要
     * @param baseline  同组合零新增停转基准摘要
     * @return 严格更优时返回true
     */
    public boolean isStrictlyBetterThanZeroPauseBaseline(OcTimelineValueSummary candidate,
                                                         OcTimelineValueSummary baseline) {
        if (candidate == null || baseline == null
                || candidate.evidenceLevel() == OcValueEvidence.Level.INSUFFICIENT
                || baseline.evidenceLevel() == OcValueEvidence.Level.INSUFFICIENT
                || candidate.hasUnprovableExistingObligationDelay()
                || baseline.hasUnprovableExistingObligationDelay()) {
            return false;
        }
        if ((candidate.monetaryValue() == null || baseline.monetaryValue() == null)
                && !priorComparable(candidate, baseline)) {
            return false;
        }
        if (candidate.existingObligationDelay().compareTo(
                baseline.existingObligationDelay()) > 0) {
            return false;
        }

        return compareTimelineValue(candidate, baseline) < 0;
    }

    /**
     * 在候选集合中选择当前最优的零新增停转替代时间线摘要。
     * 作为收益级停转候选严格优于基准比较中的真实基准：无零停转候选时返回null，
     * 调用方必须fail-closed。同等价值时保持集合内的首个，保证结果确定。
     *
     * @param candidates 已证明安全的候选集合
     * @return 最优零停转候选的时间线价值摘要；不存在时为null
     */
    public OcTimelineValueSummary bestZeroPauseBaseline(List<SafeCandidate> candidates) {
        OcTimelineValueSummary best = null;
        for (SafeCandidate candidate : candidates) {
            if (candidate.pauseTier() != SafeCandidate.PauseTier.ZERO_PAUSE
                    || candidate.timelineValue() == null) {
                continue;
            }
            if (best == null || compareTimelineValue(candidate.timelineValue(), best) < 0) {
                best = candidate.timelineValue();
            }
        }
        return best;
    }

    /**
     * 比较金额价值或第三层业务先验。金额均可用时比较金额；金额缺失时切换到
     * 等级、完整链总需人数、链节点数先验元组，金额为null不得直接降为不可比较。
     *
     * @param left  左候选摘要
     * @param right 右候选摘要
     * @return 左侧更优时返回负数；金额或先验仍不可区分时返回0
     */
    private int compareMonetaryOrPrior(OcTimelineValueSummary left,
                                       OcTimelineValueSummary right) {
        if (left.monetaryValue() != null && right.monetaryValue() != null) {
            return right.monetaryValue().compareTo(left.monetaryValue());
        }
        if (left.monetaryValue() == null && right.monetaryValue() == null) {
            return comparePrior(left, right);
        }
        return 0;
    }

    /**
     * 比较第三层业务先验元组：最高等级（高优）、完整链总需人数（高优）、
     * 链节点数（高优）、实际增量人天（低优）、保证释放时间（早优）。
     *
     * @param left  左候选摘要
     * @param right 右候选摘要
     * @return 左侧更优时返回负数；仍不可区分时返回0
     */
    private int comparePrior(OcTimelineValueSummary left,
                             OcTimelineValueSummary right) {
        int rank = Integer.compare(right.highestRank(), left.highestRank());
        if (rank != 0) {
            return rank;
        }
        int members = Integer.compare(right.totalRequiredMembers(),
                left.totalRequiredMembers());
        if (members != 0) {
            return members;
        }
        int nodes = Integer.compare(right.chainNodeCount(), left.chainNodeCount());
        if (nodes != 0) {
            return nodes;
        }
        int days = Integer.compare(left.actualIncrementalMemberDays(),
                right.actualIncrementalMemberDays());
        if (days != 0) {
            return days;
        }
        return compareRelease(left.guaranteedReleaseAt(), right.guaranteedReleaseAt());
    }

    /**
     * 判断两个金额缺失摘要的第三层先验是否可比较。
     *
     * @param left  左候选摘要
     * @param right 右候选摘要
     * @return 任一关键先验字段缺失时返回false
     */
    private boolean priorComparable(OcTimelineValueSummary left,
                                    OcTimelineValueSummary right) {
        return left.highestRank() > 0 && right.highestRank() > 0
                && left.totalRequiredMembers() > 0 && right.totalRequiredMembers() > 0
                && left.chainNodeCount() > 0 && right.chainNodeCount() > 0;
    }

    /**
     * 比较两个保证释放时间，更早者更优。
     *
     * @param leftReleaseAt  左候选保证释放时间
     * @param rightReleaseAt 右候选保证释放时间
     * @return 左侧更早时返回负数；任一缺失时返回0
     */
    public int compareRelease(LocalDateTime leftReleaseAt,
                              LocalDateTime rightReleaseAt) {
        if (leftReleaseAt == null || rightReleaseAt == null) {
            return 0;
        }
        return leftReleaseAt.compareTo(rightReleaseAt);
    }
}
