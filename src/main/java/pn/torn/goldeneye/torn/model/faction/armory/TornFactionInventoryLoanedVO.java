package pn.torn.goldeneye.torn.model.faction.armory;

import lombok.Data;

/**
 * Torn帮派库存借用信息
 *
 * @author Bai
 * @version 1.3.10
 * @since 2026.08.21
 */
@Data
public class TornFactionInventoryLoanedVO {
    /**
     * 借用人ID
     */
    private Long id;
    /**
     * 借用人名称
     */
    private String name;
}
