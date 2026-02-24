package pn.torn.goldeneye.torn.model.user.stocks;

import lombok.Data;

import java.util.List;

/**
 * Torn用户股票详情响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.09.27
 */
@Data
public class TornUserStocksDetailVO {
    /**
     * 股票ID
     */
    private int id;
    /**
     * 持股数
     */
    private long shares;
    /**
     * 分红信息
     */
    private TornUserStocksBonusVO bonus;
    /**
     * 购买记录，Key为交易ID
     */
    private List<TornUserStocksTransactionsVO> transactions;
}