package pn.torn.goldeneye.torn.model.user.stocks;

import lombok.Data;

/**
 * Torn用户股票分红响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.27
 */
@Data
public class TornUserStocksDividendVO {
    /**
     * 是否可以领取分红
     */
    private int ready;
    /**
     * 分红倍数
     */
    private int increment;
    /**
     * 当前分红周期进度
     */
    private int progress;
    /**
     * 分红周期
     */
    private int frequency;
}
