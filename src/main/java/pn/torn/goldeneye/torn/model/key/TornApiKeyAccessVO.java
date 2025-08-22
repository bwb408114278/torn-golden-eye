package pn.torn.goldeneye.torn.model.key;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Torn Key权限VO
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.21
 */
@Data
public class TornApiKeyAccessVO {
    /**
     * Key级别
     */
    private Integer level;
    /**
     * Key类型
     */
    private String type;
    /**
     * 是否有帮派权限
     */
    private Boolean faction;
}