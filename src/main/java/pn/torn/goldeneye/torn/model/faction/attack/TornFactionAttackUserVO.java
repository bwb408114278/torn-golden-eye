package pn.torn.goldeneye.torn.model.faction.attack;

import lombok.Data;

/**
 * 帮派攻击用户响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Data
public class TornFactionAttackUserVO {
    /**
     * 用户ID
     */
    private long id;
    /**
     * 用户名称
     */
    private String name;
    /**
     * 用户等级
     */
    private int level;
    /**
     * 用户帮派
     */
    private TornFactionAttackFactionVO faction;
}