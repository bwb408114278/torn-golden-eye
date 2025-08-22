package pn.torn.goldeneye.torn.model.key;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn Key用户VO
 */
@Data
public class TornApiKeyUserVO {
    /**
     * 用户ID
     */
    private long id;
    /**
     * 帮派ID
     */
    @JsonProperty("faction_id")
    private Long factionId;
}
