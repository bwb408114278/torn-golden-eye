package pn.torn.goldeneye.torn.service.stocks.alert.monthly;

/**
 * 月度状态证据指标 - 由可用15分钟bar按冻结公式计算得到的不可变指标集合。
 * <p>
 * 指标全部使用可用bar的{@code lastPrice}计算;日级对数趋势使用每个自然日
 * 最后一个可用bar价格{@code dailyClose[d]}。本record仅承载计算值,不含业务判定,
 * 分类与迟滞由 {@link StockMonthlyStateCalculator} 完成。
 *
 * @param evidenceDays           证据窗口自然日长度(duration/1天,可为小数)
 * @param fullReturn             全窗口收益 first->last
 * @param annualizedDisplay      展示年化收益(仅展示与分类,短历史禁止单独解释)
 * @param trend30                日级对数趋势外推30日收益率
 * @param trend30Low             trend30的90%置信下界
 * @param trend30High            trend30的90%置信上界
 * @param secondHalfReturn       后半段收益(以dailyClose中点基准)
 * @param lastQuarterReturn      最近90日收益;历史不足90日时等于fullReturn
 * @param fullBand               全窗口价格带 max/min-1
 * @param maxDrawdown            全窗口最大回撤(负值)
 * @param negativeMonthRatio     负月变化占比(无月变化时为空)
 * @param negativeMonthStreak    自最后一个完整自然月向前连续负变化月数
 * @param completeMonthCount     证据窗口内完整覆盖的自然月数
 * @param usableBarCoverage      可用bar数/期望15分钟桶数
 * @param maxMissingBucketGap    相邻可用bar最大间隔(分钟)
 * @param dailyCloseCount        有可用bar的自然日数(日级趋势样本数)
 * @param highVotes              HIGH风险票数(H1-H4命中数)
 * @param mediumVotes            MEDIUM风险票数(M1-M6命中数)
 * @param h1                     H1票: trend30High < -0.3%
 * @param h2                     H2票: secondHalfReturn <= -1.5% 且 trend30 < 0
 * @param h3                     H3票: lastQuarterReturn <= -2% 且 trend30 < 0
 * @param h4                     H4票: negativeMonthStreak >= 3
 * @param m1                     M1票: trend30 < -0.3%
 * @param m2                     M2票: secondHalfReturn < -0.8%
 * @param m3                     M3票: lastQuarterReturn < -1.2%
 * @param m4                     M4票: negativeMonthRatio >= 60%
 * @param m5                     M5票: negativeMonthStreak >= 2
 * @param m6                     M6票: maxDrawdown <= -4%
 * @param quarterWindowTruncated 历史不足90日导致最近季度收益被截断
 * @param complete               数据完整性是否满足月度确认最低要求
 * @param incompleteReason       不完整原因编码(完整时为空)
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockMonthlyEvidenceMetrics(
        double evidenceDays,
        double fullReturn,
        double annualizedDisplay,
        double trend30,
        double trend30Low,
        double trend30High,
        double secondHalfReturn,
        double lastQuarterReturn,
        double fullBand,
        double maxDrawdown,
        Double negativeMonthRatio,
        int negativeMonthStreak,
        int completeMonthCount,
        double usableBarCoverage,
        long maxMissingBucketGap,
        int dailyCloseCount,
        int highVotes,
        int mediumVotes,
        boolean h1,
        boolean h2,
        boolean h3,
        boolean h4,
        boolean m1,
        boolean m2,
        boolean m3,
        boolean m4,
        boolean m5,
        boolean m6,
        boolean quarterWindowTruncated,
        boolean complete,
        String incompleteReason
) {
}
