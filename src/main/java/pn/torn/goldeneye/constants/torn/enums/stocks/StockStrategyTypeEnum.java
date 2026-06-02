package pn.torn.goldeneye.constants.torn.enums.stocks;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 股票交易策略枚举
 *
 * @author Bai
 * @version 1.1.6
 * @since 2026.06.02
 */
@Getter
@AllArgsConstructor
public enum StockStrategyTypeEnum {
    /**
     * 日内均值回归
     */
    INTRADAY_MEAN_REVERSION("日内均值回归"),
    /**
     * 波段低位建仓
     */
    SWING_LOW_BUY("波段低位建仓"),
    /**
     * 低位反弹确认
     */
    SWING_REVERSAL_BUY("低位反弹确认"),
    /**
     * 波段高位止盈
     */
    SWING_TAKE_PROFIT_SELL("波段高位止盈"),
    /**
     * 反弹减仓
     */
    SWING_REBOUND_SELL("反弹减仓"),
    /**
     * 持续下跌风险
     */
    FALLING_KNIFE_RISK("持续下跌风险"),
    /**
     * 慢反弹等待确认
     */
    SLOW_REBOUND_WAIT("慢反弹等待确认"),
    /**
     * 疑似调仓
     */
    UNCERTAIN_REBALANCE("疑似调仓"),
    /**
     * 无明显策略
     */
    NONE("无明显策略");

    private final String name;
}