package pn.torn.goldeneye.torn.model.key;

import lombok.Data;

/**
 * Torn Key信息VO
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.21
 */
@Data
public class TornApiKeyInfoVO {
    /**
     * 权限
     */
    private TornApiKeyAccessVO access;
    /**
     * 用户
     */
    private TornApiKeyUserVO user;
}