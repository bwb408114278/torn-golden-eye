package pn.torn.goldeneye.torn.faction;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn帮派响应参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@Data
public class TornFactionVO {
    /**
     * 帮派ID
     */
    @JsonProperty("faction_id")
    private Long factionId;
}