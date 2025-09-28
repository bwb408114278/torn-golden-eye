package pn.torn.goldeneye.torn.model.user.stocks;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn用户股票交易记录响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.27
 */
@Data
public class TornUserStocksTransactionsVO {
    /**
     * 购买股数
     */
    private long shares;
    /**
     * 购买价格
     */
    @JsonProperty("bought_price")
    private double boughtPrice;
    /**
     * 购买时间
     */
    @JsonProperty("time_bought")
    private long timeBought;
}