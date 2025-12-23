package pn.torn.goldeneye.torn.model.faction.attack;

import lombok.Data;

/**
 * 帮派攻击帮派响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Data
public class TornFactionAttackFactionVO {
    /**
     * 帮派ID
     */
    private Long id;
    /**
     * 帮派名称
     */
    private String name;
}