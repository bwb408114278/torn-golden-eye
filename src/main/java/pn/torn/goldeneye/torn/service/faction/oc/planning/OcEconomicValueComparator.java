package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.springframework.stereotype.Component;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcValueEvidence;

import java.math.BigDecimal;
import java.util.List;

/**
 * 候选时间线经济价值比较器。只能在硬安全与完整时间线可行之后使用，固定比较顺序：
 * 禁止被迫拆队 → 已启动链和已投入义务 → 完整时间线可行性 → 规划窗口全局总价值
 * → 增量单位成员人天 → 更早释放稀缺岗位 → 稳定tie-break。
 *
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
@Component
public class OcEconomicValueComparator {

    /**
     * 比较两个已证明安全候选的价值顺序，返回-1表示left更优。
     *
     * @param leftValue       左候选窗口总价值；证据不足时为null
     * @param leftMemberDays  左候选增量剩余成员人天
     * @param leftReleaseAt   左候选最早完整释放时间；无时为null
     * @param rightValue      右候选窗口总价值；证据不足时为null
     * @param rightMemberDays 右候选增量剩余成员人天
     * @param rightReleaseAt  右候选最早完整释放时间；无时为null
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
        int perDayResult = comparePerMemberDay(leftValue, leftMemberDays,
                rightValue, rightMemberDays);
        if (perDayResult != 0) {
            return perDayResult;
        }
        return compareRelease(leftReleaseAt, rightReleaseAt);
    }

    /**
     * 比较窗口总价值。金额证据不足时标记为不可区分，由调用方降级处理。
     *
     * @param leftValue  左候选价值
     * @param rightValue 右候选价值
     * @return 价值更高一侧为优；任一侧证据不足时返回0
     */
    public int compareValues(BigDecimal leftValue, BigDecimal rightValue) {
        if (leftValue == null || rightValue == null) {
            return 0;
        }
        return rightValue.compareTo(leftValue);
    }

    /**
     * 比较增量单位成员人天收益。
     *
     * @return 单位人天收益更高一侧为优；任一侧证据不足或人天为零时返回0
     */
    public int comparePerMemberDay(BigDecimal leftValue, int leftMemberDays,
                                   BigDecimal rightValue, int rightMemberDays) {
        if (leftValue == null || rightValue == null
                || leftMemberDays <= 0 || rightMemberDays <= 0) {
            return 0;
        }
        BigDecimal leftPerDay = leftValue.divide(BigDecimal.valueOf(leftMemberDays),
                java.math.MathContext.DECIMAL64);
        BigDecimal rightPerDay = rightValue.divide(BigDecimal.valueOf(rightMemberDays),
                java.math.MathContext.DECIMAL64);
        return rightPerDay.compareTo(leftPerDay);
    }

    /**
     * 比更早完整释放时间：更早释放稀缺岗位成员的方案优先。
     *
     * @return 更早一侧为优；任一侧缺失时返回0
     */
    public int compareRelease(java.time.LocalDateTime leftReleaseAt,
                              java.time.LocalDateTime rightReleaseAt) {
        if (leftReleaseAt == null || rightReleaseAt == null) {
            return 0;
        }
        return leftReleaseAt.compareTo(rightReleaseAt);
    }

    /**
     * 判断两个候选在金额与人天维度是否仍不可稳定区分。
     *
     * @param evidences 参与比较的价值证据集合
     * @return 全部证据均无法区分时返回true
     */
    public boolean economicallyIndistinguishable(List<OcValueEvidence> evidences) {
        return evidences == null || evidences.isEmpty()
                || evidences.stream().allMatch(evidence ->
                evidence.totalValue() == null && evidence.level()
                        == OcValueEvidence.Level.PRIOR_ONLY)
                || evidences.stream().allMatch(evidence ->
                evidence.level() == OcValueEvidence.Level.INSUFFICIENT);
    }
}
