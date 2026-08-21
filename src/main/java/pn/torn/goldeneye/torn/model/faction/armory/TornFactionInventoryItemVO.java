package pn.torn.goldeneye.torn.model.faction.armory;

import lombok.Data;

import java.util.List;

/**
 * Torn帮派库存物品响应参数
 *
 * @author Bai
 * @version 1.3.10
 * @since 2026.08.21
 */
@Data
public class TornFactionInventoryItemVO {
    /**
     * 物品ID
     */
    private Long id;
    /**
     * 物品名称
     */
    private String name;
    /**
     * 物品类型
     */
    private String type;
    /**
     * 库存数量
     */
    private Integer amount;
    /**
     * 唯一物品ID
     */
    private List<Long> uids;
    /**
     * 借用信息，非空表示已借出
     */
    private TornFactionInventoryLoanedVO loaned;
}
