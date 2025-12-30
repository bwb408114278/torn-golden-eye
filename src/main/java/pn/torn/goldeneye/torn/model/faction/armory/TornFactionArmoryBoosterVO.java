package pn.torn.goldeneye.torn.model.faction.armory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn帮派物资Booster响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.30
 */
@Data
public class TornFactionArmoryBoosterVO implements TornFactionUsageItem {
    /**
     * ID
     */
    @JsonProperty("ID")
    private Long id;
    /**
     * 物品名称
     */
    private String name;
    /**
     * 类型
     */
    private String type;
    /**
     * 数量
     */
    private Integer quantity;

    @Override
    public Long getItemId() {
        return this.id;
    }

    @Override
    public Integer getAvailableQty() {
        return this.quantity;
    }
}