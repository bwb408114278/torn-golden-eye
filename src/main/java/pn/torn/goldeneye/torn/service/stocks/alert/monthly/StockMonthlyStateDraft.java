package pn.torn.goldeneye.torn.service.stocks.alert.monthly;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 月度状态计算结果 - 由 {@link StockMonthlyStateCalculator} 对单支股票按冻结公式
 * 计算得到的不可变分类结果,包含成熟度、原始/建议/最终风格、原始/有效风险与指标快照。
 * <p>
 * 服务层根据本结果组装 {@link pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO}:
 * <ul>
 *   <li>{@code manualOverride=false} 时 {@code strategyFitPrior} 等于 {@code suggestedPersonality};</li>
 *   <li>{@code confirmable=false} 时不得自动确认,继续保持DRAFT。</li>
 * </ul>
 *
 * @param stocksId             股票ID
 * @param stocksShortname      股票简称快照
 * @param effectiveMonth       生效月份(当月1日)
 * @param evidenceStartTime    证据区间起始时间
 * @param evidenceEndTime      证据区间结束时间
 * @param maturity             成熟度
 * @param rawPersonality       机器原始六分类(证据不完整时为null)
 * @param previousPersonality  上一确认月份风格(首月或缺失时为null)
 * @param suggestedPersonality 机器应用迟滞后的建议风格(证据不完整时为null)
 * @param strategyFitPrior     最终风格(manualOverride=false时等于建议;证据不完整时为null)
 * @param rawRiskLevel         机器原始风险(证据不完整时为null)
 * @param riskLevel            应用迟滞后的有效风险(证据不完整时为null)
 * @param metricSnapshot       分类时完整指标快照JSON文本(含raw值、投票明细、迟滞原因)
 * @param complete             证据数据完整性是否通过
 * @param incompleteReason     不完整原因编码(完整时为空)
 * @param confirmable          是否允许自动确认(完整性、迟滞与raw字段齐全)
 * @param hysteresisReason     迟滞决策原因(立即/两月/显著越界/恢复)
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockMonthlyStateDraft(
        Integer stocksId,
        String stocksShortname,
        LocalDate effectiveMonth,
        LocalDateTime evidenceStartTime,
        LocalDateTime evidenceEndTime,
        StockMaturityEnum maturity,
        StockStrategyFitEnum rawPersonality,
        StockStrategyFitEnum previousPersonality,
        StockStrategyFitEnum suggestedPersonality,
        StockStrategyFitEnum strategyFitPrior,
        StockRiskLevelEnum rawRiskLevel,
        StockRiskLevelEnum riskLevel,
        String metricSnapshot,
        boolean complete,
        String incompleteReason,
        boolean confirmable,
        String hysteresisReason
) {
}
