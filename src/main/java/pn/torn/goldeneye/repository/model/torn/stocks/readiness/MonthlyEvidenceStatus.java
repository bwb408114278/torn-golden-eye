package pn.torn.goldeneye.repository.model.torn.stocks.readiness;

import java.time.LocalDate;

/**
 * 逐月逐股月度证据只读状态 - 承载 {@code torn_stock_monthly_state.metric_snapshot}
 * JSONB中已有的raw/adjusted完整性字段,供就绪报告同时披露原始与豁免后口径。
 * <p>
 * 本record仅用于只读报告,不是新的持久化DO;V1快照没有新键时raw/adjusted/exclusion
 * 字段为{@code null},不得解释为0或V2合格。
 *
 * @param stocksId                    股票ID
 * @param stocksShortname             股票简称快照
 * @param effectiveMonth              生效月份
 * @param stateStatus                 月度状态(DRAFT/CONFIRMED/RETIRED)
 * @param personalityRuleVersion      风格规则版本
 * @param riskRuleVersion             风险规则版本
 * @param rawUsableBarCoverage        原始可用bar覆盖率(V1快照无此键时为null)
 * @param rawMaxMissingBucketGap      原始最大间隔分钟数(V1快照无此键时为null)
 * @param adjustedUsableBarCoverage   调整后可用bar覆盖率(V1快照无此键时为null)
 * @param adjustedMaxMissingBucketGap 调整后最大间隔分钟数(V1快照无此键时为null)
 * @param excludedBucketCount         相交排除桶数(V1快照无此键时为null)
 * @param excludedMinutes             相交排除分钟数(V1快照无此键时为null)
 * @param appliedExclusionIdsJson     实际相交排除窗口ID JSON数组文本(V1快照无此键时为null)
 * @param incompleteReason            不完整原因编码(完整时为null)
 * @author Bai
 * @version 1.4.8
 * @since 2026.08.26
 */
public record MonthlyEvidenceStatus(
        Integer stocksId,
        String stocksShortname,
        LocalDate effectiveMonth,
        String stateStatus,
        String personalityRuleVersion,
        String riskRuleVersion,
        Double rawUsableBarCoverage,
        Long rawMaxMissingBucketGap,
        Double adjustedUsableBarCoverage,
        Long adjustedMaxMissingBucketGap,
        Long excludedBucketCount,
        Long excludedMinutes,
        String appliedExclusionIdsJson,
        String incompleteReason
) {
}
