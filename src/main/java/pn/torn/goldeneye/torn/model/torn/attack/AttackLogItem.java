package pn.torn.goldeneye.torn.model.torn.attack;

import lombok.Data;

/**
 * 战斗Log使用物品响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Data
public class AttackLogItem {
    /**
     * 物品ID
     */
    private Long id;
    /**
     * 物品名称
     */
    private String name;
}