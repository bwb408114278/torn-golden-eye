package pn.torn.goldeneye.torn.service.stocks.replay.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 回放运行摘要(输出到 summary.json)。
 *
 * <p>内容完全确定性(无墙钟时间戳),可由相同输入复现。年化收益为短历史比较基准,
 * 必须携带 {@code SHORT_HISTORY_ANNUALIZED_BACKTEST} 标记,不构成收益承诺。</p>
 *
 * @param runId           回放运行标识
 * @param status          运行状态: COMPLETED/FAILED/INCOMPLETE
 * @param marker          短历史年化回放标记
 * @param startTime       回放开始时间
 * @param endTime         回放结束时间
 * @param windowDays      回放窗口自然日数
 * @param barBuildVersion bar构建规则版本
 * @param featureVersion  特征计算规则版本
 * @param buyRuleVersion  买入规则版本
 * @param sellRuleVersion 退出规则版本
 * @param tracks          各轨道摘要
 * @param error           失败原因(仅FAILED/INCOMPLETE时非空)
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockReplaySummary(
        String runId,
        String status,
        String marker,
        LocalDateTime startTime,
        LocalDateTime endTime,
        long windowDays,
        String barBuildVersion,
        String featureVersion,
        String buyRuleVersion,
        String sellRuleVersion,
        List<TrackSummary> tracks,
        String error
) {

    /**
     * 单一轨道摘要。
     *
     * @param track               轨道编码
     * @param displayName         轨道展示名
     * @param slotCount           正式槽位数
     * @param initialCashPerSlot  每槽初始资金
     * @param trades              交易总数
     * @param buys                买入数
     * @param sells               卖出数
     * @param intervalReturn      区间净收益
     * @param annualizedReturn    年化折算收益
     * @param maxDrawdown         最大回撤
     * @param slotUtilization     槽位占用率
     * @param medianHoldHours     中位持有小时数
     * @param messageCount        正式消息条数(买卖合计)
     * @param messageRoundsPerDay 每日消息轮次数
     * @param rejectionCount      拒绝/观察记录数
     * @param rejectionReasons    拒绝/观察原因分布
     * @param observationCount    完成理论观察数
     * @param observationResults  理论观察结果分布
     * @param finalEquity         期末权益
     * @param equityPoints        净值点数量
     * @param dynamicSell         动态SELL研究数据(仅该轨道非空)
     * @author Bai
     * @version 1.2.14
     * @since 2026.08.06
     */
    public record TrackSummary(
            String track,
            String displayName,
            int slotCount,
            BigDecimal initialCashPerSlot,
            long trades,
            long buys,
            long sells,
            BigDecimal intervalReturn,
            BigDecimal annualizedReturn,
            BigDecimal maxDrawdown,
            BigDecimal slotUtilization,
            BigDecimal medianHoldHours,
            long messageCount,
            BigDecimal messageRoundsPerDay,
            long rejectionCount,
            Map<String, Integer> rejectionReasons,
            long observationCount,
            Map<String, Integer> observationResults,
            BigDecimal finalEquity,
            long equityPoints,
            DynamicSellSummary dynamicSell
    ) {
    }

    /**
     * 动态SELL研究数据摘要(公式冻结前固定 decision/reason,建议与交易为0)。
     *
     * @param decision         动态影子决定(固定NOT_EVALUATED)
     * @param reason           未评估原因(固定DYNAMIC_RULE_NOT_FROZEN)
     * @param observations     采集的研究输入条数
     * @param inputCoverage    研究输入覆盖率(0~1)
     * @param missingRate      研究输入缺失率(0~1)
     * @param pathDistribution 研究输入路径分布(按买入策略族)
     * @param suggestions      动态SELL建议数(冻结前恒为0)
     * @param trades           动态SELL交易数(冻结前恒为0)
     * @param closes           动态SELL关闭数(冻结前恒为0)
     * @author Bai
     * @version 1.2.14
     * @since 2026.08.06
     */
    public record DynamicSellSummary(
            String decision,
            String reason,
            long observations,
            BigDecimal inputCoverage,
            BigDecimal missingRate,
            Map<String, Integer> pathDistribution,
            long suggestions,
            long trades,
            long closes
    ) {
    }

    /**
     * 构建空的原因分布(确定性有序)。
     *
     * @return 空原因分布
     */
    public static SortedMap<String, Integer> emptyReasonMap() {
        return new TreeMap<>();
    }

    /**
     * 合并原因计数(确定性有序)。
     *
     * @param target 目标分布
     * @param reason 原因编码
     */
    public static void mergeReason(Map<String, Integer> target, String reason) {
        String key = reason == null || reason.isBlank() ? "UNKNOWN" : reason;
        target.merge(key, 1, Integer::sum);
    }
}
