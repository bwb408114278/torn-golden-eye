package pn.torn.goldeneye.torn.model.user.cooldown;

import lombok.Data;

/**
 * 用户冷却响应VO
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.12
 */
@Data
public class TornUserCooldownVO {
    /**
     * 冷却信息
     */
    private TornUserCooldownDataVO cooldowns;
}