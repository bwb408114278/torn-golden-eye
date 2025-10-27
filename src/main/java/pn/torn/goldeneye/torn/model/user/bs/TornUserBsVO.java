package pn.torn.goldeneye.torn.model.user.bs;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn用户BS响应参数
 *
 * @author Bai
 * @version 1.0
 * @since 2025.10.27
 */
@Data
public class TornUserBsVO {
    /**
     * 战斗属性
     */
    @JsonProperty("battlestats")
    private TornUserBsDetailVO battleStats;
}
