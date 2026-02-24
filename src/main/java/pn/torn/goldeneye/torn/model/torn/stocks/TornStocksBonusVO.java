package pn.torn.goldeneye.torn.model.torn.stocks;

import lombok.Data;

/**
 * Torn股票分红响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.09.26
 */
@Data
public class TornStocksBonusVO {
    /**
     * 收益类型
     */
    private boolean passive;
    /**
     * 收益周期
     */
    private int frequency;
    /**
     * 所需股数
     */
    private long requirement;
    /**
     * 分红描述
     */
    private String description;
}