package pn.torn.goldeneye.repository.model.faction.armory;

import lombok.Data;

/**
 * 物资毁灭者查询结果
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.08.08
 */
@Data
public class ItemUseRankingDO {
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 用户昵称
     */
    private String nickname;
    /**
     * 总数
     */
    private Integer total;
}