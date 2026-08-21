package pn.torn.goldeneye.torn.model.faction.armory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Torn帮派库存响应参数
 *
 * @author Bai
 * @version 1.3.10
 * @since 2026.08.21
 */
@Data
public class TornFactionInventoryVO {
    /**
     * 库存更新时间
     */
    @JsonProperty("inventory_timestamp")
    private Long inventoryTimestamp;
    /**
     * 库存物品
     */
    private List<TornFactionInventoryItemVO> inventory;
    /**
     * 分页元数据
     */
    @JsonProperty("_metadata")
    private TornFactionInventoryMetadataVO metadata;
}
