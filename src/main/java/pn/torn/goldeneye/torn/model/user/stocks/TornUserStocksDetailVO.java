package pn.torn.goldeneye.torn.model.user.stocks;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * Torn用户股票详情响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.27
 */
@Data
public class TornUserStocksDetailVO {
    /**
     * 股票ID
     */
    @JsonProperty("stock_id")
    private int stockId;
    /**
     * 持股数
     */
    @JsonProperty("total_shares")
    private long totalShares;
    /**
     * 分红信息
     */
    private TornUserStocksDividendVO dividend;
    /**
     * 购买记录，Key为交易ID
     */
    private Map<String, TornUserStocksTransactionsVO> transactions;
}