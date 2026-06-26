package pn.torn.goldeneye.torn.model.faction.crime;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * OC岗位需要物品响应参数
 *
 * @author Bai
 * @version 1.2.5
 * @since 2026.06.23
 */
@Data
public class TornFactionCrimeRequireItemVO {
    /**
     * 物品ID
     */
    private Integer id;
    /**
     * 是否可重复使用
     */
    @JsonProperty("is_reusable")
    private Boolean isReusable;
    /**
     * 成员是否拥有该道具
     */
    @JsonProperty("is_available")
    private Boolean isAvailable;
}
