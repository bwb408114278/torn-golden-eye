package pn.torn.goldeneye.torn.service.stocks.alert.buy;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;

import java.math.BigDecimal;

/**
 * 买入策略评估上下文，封装一只股票在当前轮次中参与策略匹配所需的全部特征与月度状态信息。
 * <p>
 * 该record为不可变值对象，由调用方从 {@code TornStockStrategyFeature15mDO} 与
 * {@code TornStockMonthlyStateDO} 中提取字段组装后传入策略接口，避免策略实现直接依赖DO。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
public record BuyContext(
        Integer stocksId,
        String stocksShortname,
        BigDecimal referencePrice,
        BigDecimal ma1d,
        BigDecimal ma7d,
        BigDecimal ma30d,
        BigDecimal zscore1d,
        BigDecimal zscore7d,
        BigDecimal zscore30d,
        BigDecimal return6h,
        BigDecimal return1d,
        BigDecimal return7d,
        BigDecimal return14d,
        BigDecimal low30d,
        BigDecimal high30d,
        BigDecimal width30d,
        BigDecimal position30,
        BigDecimal pctAbove30dLow,
        BigDecimal pctBelow30dHigh,
        Boolean strategyReady,
        StockStrategyFitEnum stylePrior,
        StockMaturityEnum maturity,
        StockRiskLevelEnum riskLevel
) {
}
