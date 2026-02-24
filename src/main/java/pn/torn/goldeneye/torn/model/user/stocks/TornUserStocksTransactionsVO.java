package pn.torn.goldeneye.torn.model.user.stocks;

import lombok.Data;

/**
 * Torn用户股票交易记录响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.09.27
 */
@Data
public class TornUserStocksTransactionsVO {
    /**
     * 交易ID
     */
    private long id;
    /**
     * 购买股数
     */
    private long shares;
    /**
     * 购买价格
     */
    private double price;
    /**
     * 购买时间
     */
    private long timestamp;
}