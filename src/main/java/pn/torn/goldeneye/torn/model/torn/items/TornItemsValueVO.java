package pn.torn.goldeneye.torn.model.torn.items;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn物品价值响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.26
 */
@Data
public class TornItemsValueVO {
    /**
     * 购买价
     */
    @JsonProperty("buy_price")
    private long buyPrice;
    /**
     * 卖出价
     */
    @JsonProperty("sell_price")
    private Long sellPrice;
    /**
     * 市场价
     */
    @JsonProperty("market_price")
    private long marketPrice;
}