package pn.torn.goldeneye.torn.model.faction.crime;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn OC用户消耗物品详情响应参数
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.10.30
 */
@Data
public class TornFactionCrimeUserItemVO {
    /**
     * 物品ID
     */
    @JsonProperty("item_id")
    private Integer itemId;
    /**
     * 消耗情况
     */
    private String outcome;
}