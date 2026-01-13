package pn.torn.goldeneye.torn.model.torn.auction;

import lombok.Data;

/**
 * Torn拍卖用户响应参数
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.13
 */
@Data
public class TornAuctionUserVO {
    /**
     * 用户ID
     */
    private long id;
    /**
     * 用户名称
     */
    private String name;
}