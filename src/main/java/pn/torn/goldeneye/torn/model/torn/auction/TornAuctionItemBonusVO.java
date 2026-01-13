package pn.torn.goldeneye.torn.model.torn.auction;

import lombok.Data;

/**
 * Torn拍卖物品加成响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Data
public class TornAuctionItemBonusVO {
    /**
     * 加成ID
     */
    private int id;
    /**
     * 加成名称
     */
    private String title;
    /**
     * 加成描述
     */
    private String description;
    /**
     * 加成值
     */
    private int value;
}