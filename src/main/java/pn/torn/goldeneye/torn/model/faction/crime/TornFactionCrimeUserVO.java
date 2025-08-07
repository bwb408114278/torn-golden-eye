package pn.torn.goldeneye.torn.model.faction.crime;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn OC User详情响应参数
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@Data
public class TornFactionCrimeUserVO {
    /**
     * 用户ID
     */
    private Long id;
    /**
     * 加入时间
     */
    @JsonProperty("joined_at")
    private Long joinedAt;
}