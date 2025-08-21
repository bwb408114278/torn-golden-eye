package pn.torn.goldeneye.torn.model.key;

import lombok.Data;

/**
 * Torn Key VO
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.21
 */
@Data
public class TornApiKeyVO {
    /**
     * Key信息
     */
    private TornApiKeyInfoVO info;
}