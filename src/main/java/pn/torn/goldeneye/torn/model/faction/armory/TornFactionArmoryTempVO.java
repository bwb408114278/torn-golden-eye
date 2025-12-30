package pn.torn.goldeneye.torn.model.faction.armory;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn帮派物资投掷武器响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.30
 */
@Data
public class TornFactionArmoryTempVO implements TornFactionUsageItem {
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
    /**
     * 可用数量`
     */
    private Integer available;
    /**
     * 借走数量
     */
    private Integer loaned;
    /**
     * 借走人ID，逗号分隔
     */
    @JsonProperty("loaned_to")
    private String loanedTo;

    @Override
    public Long getItemId() {
        return this.id;
    }

    @Override
    public Integer getAvailableQty() {
        return this.available;
    }
}