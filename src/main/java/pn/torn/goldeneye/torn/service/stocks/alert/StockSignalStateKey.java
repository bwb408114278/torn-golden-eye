package pn.torn.goldeneye.torn.service.stocks.alert;

import java.util.Objects;

/**
 * 股票信号状态复合键 - 按(stocksId, strategyType, buyRuleVersion)唯一标识一条信号状态
 * <p>
 * 对应数据库 {@code torn_stock_signal_state} 的唯一索引
 * {@code uk_stock_signal_state_stock_strat_ver},
 * 用于替代按单股票ID索引导致同股多策略状态互相覆盖的问题。
 *
 * @param stocksId       股票ID
 * @param strategyType   策略类型编码
 * @param buyRuleVersion 买入规则版本
 * @author Bai
 * @version 1.2.13
 * @since 2026.07.27
 */
public record StockSignalStateKey(
        Integer stocksId,
        String strategyType,
        String buyRuleVersion
) {

    /**
     * 创建信号状态复合键。
     *
     * @param stocksId       股票ID
     * @param strategyType   策略类型编码
     * @param buyRuleVersion 买入规则版本
     */
    public StockSignalStateKey {
        Objects.requireNonNull(stocksId, "股票ID不能为空");
        Objects.requireNonNull(strategyType, "策略类型不能为空");
        Objects.requireNonNull(buyRuleVersion, "买入规则版本不能为空");
    }

    /**
     * 从信号状态DO构建复合键。
     *
     * @param state 信号状态DO
     * @return 复合键;入参为null时返回null
     */
    public static StockSignalStateKey of(pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO state) {
        if (state == null || state.getStocksId() == null
                || state.getStrategyType() == null || state.getBuyRuleVersion() == null) {
            return null;
        }
        return new StockSignalStateKey(state.getStocksId(), state.getStrategyType(), state.getBuyRuleVersion());
    }
}
