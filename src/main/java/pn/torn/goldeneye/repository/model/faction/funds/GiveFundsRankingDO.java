package pn.torn.goldeneye.repository.model.faction.funds;

import lombok.Data;

/**
 * 取钱记录排行榜查询结果
 *
 * @author Bai
 * @version 0.4.0
 * @since 2026.01.12
 */
@Data
public class GiveFundsRankingDO {
    /**
     * 用户ID
     */
    private Long handleUserId;
    /**
     * 用户昵称
     */
    private String nickname;
    /**
     * 总次数
     */
    private Integer total;
    /**
     * 总金额
     */
    private Long totalAmount;
}