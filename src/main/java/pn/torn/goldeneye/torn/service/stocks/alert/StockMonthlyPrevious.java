package pn.torn.goldeneye.torn.service.stocks.alert;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;

/**
 * 上一确认月份状态 - 供月度迟滞计算读取的不可变参考。
 * <p>
 * {@code previousPersonality} 与 {@code previousRiskLevel} 来自上一确认状态的
 * {@code strategyFitPrior / riskLevel};{@code previousRawPersonality} 与
 * {@code previousRawRiskLevel} 来自其{@code metricSnapshot}的稳定raw字段,
 * 用于NARROW↔RANGING两月迟滞与风险解除判断。
 *
 * @param previousPersonality    上一确认月份最终风格(无历史时为null)
 * @param previousRiskLevel      上一确认月份有效风险(无历史时为null)
 * @param previousRawPersonality 上一确认月份raw风格(历史快照缺raw字段时为null)
 * @param previousRawRiskLevel   上一确认月份raw风险(历史快照缺raw字段时为null)
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public record StockMonthlyPrevious(
        StockStrategyFitEnum previousPersonality,
        StockRiskLevelEnum previousRiskLevel,
        StockStrategyFitEnum previousRawPersonality,
        StockRiskLevelEnum previousRawRiskLevel
) {
}
