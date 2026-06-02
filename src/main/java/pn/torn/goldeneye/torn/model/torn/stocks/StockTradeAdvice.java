package pn.torn.goldeneye.torn.model.torn.stocks;

import pn.torn.goldeneye.constants.torn.enums.stocks.StockStrategyTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.StockTradeActionEnum;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 股票交易建议
 *
 * @param stocksId         股票ID
 * @param stocksShortname  股票简称
 * @param action           操作类型
 * @param strategyType     策略类型
 * @param analysisTime     分析时间
 * @param basePrice        基础价格
 * @param score            操作评分
 * @param ma1d             近1日均价
 * @param ma7d             近7日均价
 * @param ma30d            近30日均价
 * @param zScore1d         近1日价格偏离度
 * @param zScore7d         近7日价格偏离度
 * @param zScore30d        近30日价格偏离度
 * @param rsi              RSI指标
 * @param return1d         近1日收益率
 * @param return7d         近7日收益率
 * @param return14d        近14日收益率
 * @param pctAbove30dLow   距离30日低点涨幅
 * @param pctBelow30dHigh  距离30日高点跌幅
 * @param slowReboundStock 是否慢反弹股票
 * @param fallingKnifeRisk 是否可能飞刀
 * @param reasons          操作理由
 * @author Bai
 * @version 1.1.6
 * @since 2026.06.02
 */
public record StockTradeAdvice(
        int stocksId,
        String stocksShortname,
        StockTradeActionEnum action,
        StockStrategyTypeEnum strategyType,
        LocalDateTime analysisTime,
        BigDecimal basePrice,
        BigDecimal score,
        BigDecimal ma1d,
        BigDecimal ma7d,
        BigDecimal ma30d,
        BigDecimal zScore1d,
        BigDecimal zScore7d,
        BigDecimal zScore30d,
        BigDecimal rsi,
        BigDecimal return1d,
        BigDecimal return7d,
        BigDecimal return14d,
        BigDecimal pctAbove30dLow,
        BigDecimal pctBelow30dHigh,
        boolean slowReboundStock,
        boolean fallingKnifeRisk,
        List<String> reasons) {
    public String getActionName() {
        return action.getName();
    }

    public String getStrategyName() {
        return strategyType.getName();
    }
}