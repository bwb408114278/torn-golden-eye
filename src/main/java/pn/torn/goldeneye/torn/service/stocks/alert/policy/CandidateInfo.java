package pn.torn.goldeneye.torn.service.stocks.alert.policy;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;

import java.math.BigDecimal;
import java.util.List;

/**
 * 买入候选信息，封装一只股票在当前轮次中通过策略匹配后参与正式槽位竞争的全部信息。
 * <p>
 * 当同一股票命中多个策略时，{@link #primaryStrategy} 为质量分最高的策略，
 * {@link #matchedStrategies} 记录全部命中策略编码，仅创建一个候选。
 *
 * @param stocksId          股票ID
 * @param stocksShortname   股票简称
 * @param primaryStrategy   主策略编码（质量分最高的策略）
 * @param matchedStrategies 全部命中策略编码列表
 * @param qualityScore      主策略的质量分，用于跨股票候选排序
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
public record CandidateInfo(
        Integer stocksId,
        String stocksShortname,
        StockBuyStrategyEnum primaryStrategy,
        List<String> matchedStrategies,
        BigDecimal qualityScore
) {
}
