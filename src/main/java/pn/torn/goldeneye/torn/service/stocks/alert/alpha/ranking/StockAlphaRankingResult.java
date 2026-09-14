package pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking;

import java.math.BigDecimal;

/**
 * α策略股票排名结果。
 *
 * @param stocksId     股票ID
 * @param r20          20日收益
 * @param r1           1日收益
 * @param r20Rank      20日归一化名次
 * @param r1Rank       1日归一化名次
 * @param alphaScore   综合评分
 * @param rankPosition 最终名次
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
public record StockAlphaRankingResult(
        Integer stocksId,
        BigDecimal r20,
        BigDecimal r1,
        BigDecimal r20Rank,
        BigDecimal r1Rank,
        BigDecimal alphaScore,
        int rankPosition) {
}
