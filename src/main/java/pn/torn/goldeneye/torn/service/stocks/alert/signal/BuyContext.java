package pn.torn.goldeneye.torn.service.stocks.alert.signal;

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
 * @param stocksId        股票ID
 * @param stocksShortname 股票简称
 * @param referencePrice  当前bar参考价格(最后实际价格)
 * @param ma1d            1日bar均价
 * @param ma7d            7日bar均价
 * @param ma30d           30日bar均价
 * @param zscore1d        1日标准化偏离
 * @param zscore7d        7日标准化偏离
 * @param zscore30d       30日标准化偏离
 * @param return6h        6小时收益率
 * @param return1d        1日收益率
 * @param return7d        7日收益率
 * @param return14d       14日收益率
 * @param low30d          30日最低价
 * @param high30d         30日最高价
 * @param width30d        30日价格带宽
 * @param position30      30日区间位置(高低价相同时为null)
 * @param pctAbove30dLow  距30日低点涨幅
 * @param pctBelow30dHigh 距30日高点跌幅
 * @param strategyReady   策略特征是否就绪
 * @param stylePrior      策略适配风格
 * @param maturity        成熟度
 * @param riskLevel       风险等级
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
