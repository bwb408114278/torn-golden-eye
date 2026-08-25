package pn.torn.goldeneye.torn.model.faction.attack;

import lombok.Data;

import java.math.BigDecimal;

/**
 * RW对冲窗口内的用户出手统计对象。
 *
 * @author Bai
 * @version 1.4.5
 * @since 2026.08.24
 */
@Data
public class RwUserAttackStatVO {
    /**
     * 攻击方帮派ID。
     */
    private Long attackFactionId;

    /**
     * 攻击用户ID。
     */
    private Long userId;

    /**
     * 攻击用户昵称。
     */
    private String nickname;

    /**
     * 用户出手次数。
     */
    private Integer attackCount;

    /**
     * 用户每分钟出手频率，按用户首末出手时长计算（全窗口合并时为各窗口首末时长求和），
     * 首末同秒（含单次出手）时为null。
     */
    private BigDecimal attackRatePerMinute;
}
