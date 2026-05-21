package pn.torn.goldeneye.repository.model.torn;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 个人防御统计数据
 *
 * @author Bai
 * @version 1.1.4
 * @since 2026.05.21
 */
@Data
public class PlayerDefendStatDO {
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 用户昵称
     */
    private String nickname;
    /**
     * 被攻击次数
     */
    private Integer hitNum;
    /**
     * 被爆头次数
     */
    private Integer headHitNum;
    /**
     * 被爆头伤害
     */
    private Integer headHitDamage;
    /**
     * 被爆头几率
     */
    private BigDecimal headHitRate;
}