package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 一个随机结果组合完成时间线模拟后的真实价值摘要。与静态模板证据{@link OcValueEvidence}
 * 分层：本对象只描述当前组合模拟实际发生的金额/先验、实际人天、实际停转、
 * 已投入义务完成延迟、计划内无人OC是否可避免过期和保证释放时间，不直接等同于
 * 新刷新根模板奖励之和。
 *
 * @param monetaryValue               完整窗口内金额价值；金额层级不足时为null
 * @param actualIncrementalMemberDays 实际增量剩余成员人天
 * @param actualNewPause              实际单次最大主动新增停转时长
 * @param existingObligationDelay     既有队或已启动链相对无主动停转进度的完成延迟；无延迟时为零，基准不可证明时为{@link #UNPROVEN_OBLIGATION_DELAY}
 * @param avoidableExpiryPressure     计划内无人OC是否可避免过期压力
 * @param guaranteedReleaseAt         本组合最早完整释放时间；无释放事件时为null
 * @param highestRank                 完整候选（含高阶链全部后继）的最高等级
 * @param totalRequiredMembers        完整候选（含高阶链全部后继）的总需人数
 * @param chainNodeCount              完整候选的链节点数；普通OC为1
 * @param evidenceLevel               本组合价值证据层级
 */
public record OcTimelineValueSummary(
        BigDecimal monetaryValue,
        int actualIncrementalMemberDays,
        Duration actualNewPause,
        Duration existingObligationDelay,
        boolean avoidableExpiryPressure,
        LocalDateTime guaranteedReleaseAt,
        int highestRank,
        int totalRequiredMembers,
        int chainNodeCount,
        OcValueEvidence.Level evidenceLevel) {

    /**
     * 既有义务完成延迟基准不可证明时的不可比较哨兵。
     * 收益级停转候选遇此值必须fail-closed，不得视为零延迟。
     */
    public static final Duration UNPROVEN_OBLIGATION_DELAY =
            Duration.ofSeconds(Long.MAX_VALUE);

    public OcTimelineValueSummary {
        actualNewPause = actualNewPause == null ? Duration.ZERO : actualNewPause;
        existingObligationDelay = existingObligationDelay == null
                ? Duration.ZERO : existingObligationDelay;
        evidenceLevel = evidenceLevel == null ? OcValueEvidence.Level.INSUFFICIENT
                : evidenceLevel;
    }

    /**
     * 判断既有义务完成延迟是否因基准不可证明而不可比较。
     *
     * @return 基准不可证明时返回true
     */
    public boolean hasUnprovableExistingObligationDelay() {
        return UNPROVEN_OBLIGATION_DELAY.equals(existingObligationDelay);
    }

    /**
     * 构造一个不可比较的空白价值摘要。
     *
     * @return 金额为空、人天为零、证据不足的空摘要
     */
    public static OcTimelineValueSummary empty() {
        return new OcTimelineValueSummary(null, 0, Duration.ZERO, Duration.ZERO,
                false, null, 0, 0, 1, OcValueEvidence.Level.INSUFFICIENT);
    }
}
