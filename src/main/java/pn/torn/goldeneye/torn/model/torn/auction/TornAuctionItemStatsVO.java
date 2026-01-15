package pn.torn.goldeneye.torn.model.torn.auction;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Torn拍卖物品属性响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Data
public class TornAuctionItemStatsVO {
    /**
     * 伤害
     */
    private BigDecimal damage;
    /**
     * 命中
     */
    private BigDecimal accuracy;
    /**
     * 护甲
     */
    private BigDecimal armor;
    /**
     * 品质
     */
    private BigDecimal quality;
}