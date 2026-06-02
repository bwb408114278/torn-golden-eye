package pn.torn.goldeneye.repository.model.torn;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 股票交易建议
 *
 * @param stocksId          股票ID
 * @param stocksShortname   股票简称
 * @param featureTime       分析时间
 * @param basePrice         基础价格
 * @param ma1d              近1日均价
 * @param ma7d              近7日均价
 * @param ma30d             近30日均价
 * @param zScore1d          近1日价格偏离度
 * @param zScore7d          近7日价格偏离度
 * @param zScore30d         近30日价格偏离度
 * @param rsi               RSI指标
 * @param return1d          近1日收益率
 * @param return7d          近7日收益率
 * @param return14d         近14日收益率
 * @param pctAbove30dLow    距离30日低点涨幅
 * @param pctBelow30dHigh   距离30日高点跌幅
 * @param low30d            30天低价
 * @param high30d           30天高价
 * @param latestInvestors   最后投资者人数
 * @param investorsChange7d 投资人数7天变化
 * @author Bai
 * @version 1.1.6
 * @since 2026.06.02
 */
public record StockStrategyFeatureUpsert(
        int stocksId,
        String stocksShortname,
        LocalDateTime featureTime,
        BigDecimal basePrice,
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
        BigDecimal low30d,
        BigDecimal high30d,
        Integer latestInvestors,
        Integer investorsChange7d) {
}