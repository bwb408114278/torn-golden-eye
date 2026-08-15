package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 有限事件时间线求解结果。持有整体安全评估、模式无关的已证明安全候选向量与求解耗时。
 *
 * @param assessment    时间线安全评估
 * @param candidates    已证明安全且已评分的候选向量，模式无关
 * @param lowerBound    建议是否仅为已证明刷新向量下界
 * @param elapsedMillis 求解耗时毫秒数
 * @param warnings      求解警告
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcRefreshSafetyResult(
        OcTimelineSafetyAssessment assessment,
        List<SafeCandidate> candidates,
        boolean lowerBound,
        long elapsedMillis,
        List<String> warnings) {
    public OcRefreshSafetyResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 一个已证明安全的刷新向量及其模式无关的时间线评分。
     *
     * @param vector                  刷新向量
     * @param pauseTier               证明该向量安全所需的最小停转层级
     * @param windowValue             规划窗口全局总价值；证据不足时为null
     * @param incrementalMemberDays   增量剩余成员人天
     * @param earliestCompletionAt    最早完整释放时间
     * @param anchorCount             已证明流动性锚点数量
     * @param valueEvidenceLevel      价值证据层级
     * @param usableForAdviceIncrease 全部组合证据是否可用于提高刷新建议
     */
    public record SafeCandidate(
            OcRefreshVector vector,
            PauseTier pauseTier,
            BigDecimal windowValue,
            int incrementalMemberDays,
            LocalDateTime earliestCompletionAt,
            int anchorCount,
            OcValueEvidence.Level valueEvidenceLevel,
            boolean usableForAdviceIncrease) {

        /**
         * 候选证明安全所需的最小停转容忍层级。
         */
        public enum PauseTier {
            /**
             * 全部随机组合均可在零新增停转下排程。
             */
            ZERO_PAUSE,
            /**
             * 存在组合需要不超过6小时的可恢复停转。
             */
            WITHIN_BALANCED,
            /**
             * 存在组合需要不超过12小时的可恢复停转。
             */
            WITHIN_PROFIT
        }
    }
}
