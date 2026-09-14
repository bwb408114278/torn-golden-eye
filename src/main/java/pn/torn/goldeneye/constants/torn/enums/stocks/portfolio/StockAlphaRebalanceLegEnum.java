package pn.torn.goldeneye.constants.torn.enums.stocks.portfolio;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * α换仓腿标识枚举 - 同一次α目标变化换仓的SELL腿与BUY腿。
 * <p>
 * 腿标识与腿序号是换仓通知审计的冻结口径:SELL腿固定为1、BUY腿固定为2,
 * 业务Review可据此从两条通知直接还原一次完整换仓的腿顺序。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.11
 */
@Getter
@RequiredArgsConstructor
public enum StockAlphaRebalanceLegEnum {
    /**
     * 原仓卖出腿 - 换仓中被换出的原α批次
     */
    SELL("SELL", 1),
    /**
     * 新仓买入腿 - 换仓后新的Top1目标批次
     */
    BUY("BUY", 2),
    ;

    /**
     * 腿标识编码
     */
    private final String code;
    /**
     * 腿顺序(数值越小越靠前)
     */
    private final int legOrder;
}
