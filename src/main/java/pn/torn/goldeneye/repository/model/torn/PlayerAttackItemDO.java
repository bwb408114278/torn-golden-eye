package pn.torn.goldeneye.repository.model.torn;

import lombok.Data;

/**
 * 个人攻击物品数据
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.29
 */
@Data
public class PlayerAttackItemDO {
    private String attackerItemName;
    private Integer num;
}
