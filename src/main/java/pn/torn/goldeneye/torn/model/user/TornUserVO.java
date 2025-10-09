package pn.torn.goldeneye.torn.model.user;

import lombok.Data;

/**
 * Torn用户响应参数
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.07.24
 */
@Data
public class TornUserVO {
    /**
     * 用户信息
     */
    private TornUserProfileVO profile;
}