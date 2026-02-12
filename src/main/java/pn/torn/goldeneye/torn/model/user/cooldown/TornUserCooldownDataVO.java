package pn.torn.goldeneye.torn.model.user.cooldown;

import lombok.Data;

/**
 * 用户冷却VO
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.12
 */
@Data
public class TornUserCooldownDataVO {
    /**
     * 药CD
     */
    private long drug;
    /**
     * 医疗CD
     */
    private long medical;
    /**
     * 增益品CD
     */
    private long booster;
}