package pn.torn.goldeneye.repository.model.torn;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 个人攻击统计数据
 *
 * @author Bai
 * @version 1.0
 * @since 2025.12.25
 */
@Data
public class PlayerAttackStatDO {
    /**
     * 用户ID
     */
    private Long userId;
    /**
     * 用户昵称
     */
    private String nickname;
    /**
     * 攻击次数
     */
    private Integer totalAttacks;
    /**
     * Hosp次数
     */
    private Integer hospCount;
    /**
     * Leave次数
     */
    private Integer leaveCount;
    /**
     * 助攻次数
     */
    private Integer assistCount;
    /**
     * 失败次数
     */
    private Integer lostCount;
    /**
     * 进攻回合数
     */
    private Integer totalRounds;
    /**
     * 造成伤害
     */
    private Long damageDealt;
    /**
     * 承受伤害
     */
    private Long damageTaken;
    /**
     * 打针次数
     */
    private Integer syringeUsed;
    /**
     * 特殊子弹使用回合数
     */
    private Integer specialAmmoRounds;
    /**
     * Debuff投掷使用数
     */
    private Integer debuffTempCount;
    /**
     * 总战斗耗时
     */
    private Long totalCombatDuration;
    /**
     * 平拒绝战斗耗时
     */
    private BigDecimal avgCombatDuration;
    /**
     * 攻击在线对手次数
     */
    private Integer onlineOpponentCount;
    /**
     * 对手平均ELO
     */
    private BigDecimal avgOpponentElo;
}
