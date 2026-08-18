package pn.torn.goldeneye.torn.model.faction.crime.planning;

import java.util.List;

/**
 * 有限事件时间线求解结果。持有整体安全评估、模式无关的已证明安全候选向量、
 * 求解耗时与匿名搜索遥测。
 *
 * @param assessment         时间线安全评估
 * @param candidates         已证明安全且已评分的候选向量，模式无关
 * @param lowerBound         建议是否仅为已证明刷新向量下界
 * @param elapsedMillis      求解耗时毫秒数
 * @param searchTelemetry    匿名搜索遥测，只含规模与预算命中计数
 * @param warnings           求解警告
 * @param zeroPauseBaseline  阶段二确定的全局零停转基准候选
 * @param baselineComparable 全局零停转基准是否具备收益比较条件
 * @author Bai
 * @version 1.3.0
 * @since 2026.08.15
 */
public record OcRefreshSafetyResult(
        OcTimelineSafetyAssessment assessment,
        List<SafeCandidate> candidates,
        boolean lowerBound,
        long elapsedMillis,
        OcSearchTelemetry searchTelemetry,
        List<String> warnings,
        SafeCandidate zeroPauseBaseline,
        boolean baselineComparable) {
    /**
     * 兼容不携带基准证据的旧结果构造路径；缺失基准时收益模式必须fail-closed。
     *
     * @param assessment      时间线安全评估
     * @param candidates      已证明安全的候选集合
     * @param lowerBound      是否仅为已证明刷新向量下界
     * @param elapsedMillis   求解耗时毫秒数
     * @param searchTelemetry 匿名搜索遥测
     * @param warnings        求解警告
     */
    public OcRefreshSafetyResult(OcTimelineSafetyAssessment assessment,
                                 List<SafeCandidate> candidates,
                                 boolean lowerBound,
                                 long elapsedMillis,
                                 OcSearchTelemetry searchTelemetry,
                                 List<String> warnings) {
        this(assessment, candidates, lowerBound, elapsedMillis, searchTelemetry, warnings,
                null, false);
    }

    public OcRefreshSafetyResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        searchTelemetry = searchTelemetry == null
                ? OcSearchTelemetry.empty() : searchTelemetry;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 一个已证明安全的刷新向量及其模式无关的时间线评分。
     *
     * @param vector                                   刷新向量
     * @param pauseTier                                全部随机组合所需的最严格停转层级
     * @param timelineValue                            全部随机组合聚合后的真实时间线价值摘要
     * @param anchorCount                              已证明流动性锚点数量
     * @param valueEvidenceLevel                       最弱组合的价值证据层级
     * @param zeroPauseBaselineComparable              全部相关组合是否均存在可比较的零新增停转基准
     * @param pauseCandidateStrictlyBetterThanBaseline 含主动新增停转的收益候选是否严格优于零停转基准
     */
    public record SafeCandidate(
            OcRefreshVector vector,
            PauseTier pauseTier,
            OcTimelineValueSummary timelineValue,
            int anchorCount,
            OcValueEvidence.Level valueEvidenceLevel,
            boolean zeroPauseBaselineComparable,
            boolean pauseCandidateStrictlyBetterThanBaseline) {

        /**
         * 获取聚合后的保证最早释放时间：全部随机组合最早完整释放中的最晚值。
         *
         * @return 保证释放时间；任一组合无释放事件时为null
         */
        public java.time.LocalDateTime guaranteedEarliestReleaseAt() {
            return timelineValue == null ? null : timelineValue.guaranteedReleaseAt();
        }

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
