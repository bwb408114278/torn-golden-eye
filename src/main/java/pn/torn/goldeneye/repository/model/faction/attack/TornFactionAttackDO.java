package pn.torn.goldeneye.repository.model.faction.attack;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 帮派攻击记录表
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_attack", autoResultMap = true)
public class TornFactionAttackDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 攻方用户ID
     */
    private Long attackUserId;
    /**
     * 攻方用户昵称
     */
    private String attackUserNickname;
    /**
     * 攻方帮派ID
     */
    private Long attackFactionId;
    /**
     * 攻方帮派名称
     */
    private String attackFactionName;
    /**
     * 守方用户ID
     */
    private Long defendUserId;
    /**
     * 守方用户昵称
     */
    private String defendUserNickname;
    /**
     * 守方帮派ID
     */
    private Long defendFactionId;
    /**
     * 守方帮派名称
     */
    private String defendFactionName;
    /**
     * 守方在线状态
     */
    private String defendUserOnlineStatus;
    /**
     * 攻击开始时间
     */
    private LocalDateTime attackStartTime;
    /**
     * 攻击结束时间
     */
    private LocalDateTime attackEndTime;
    /**
     * 攻击结果
     */
    private String attackResult;
    /**
     * 攻击LogId
     */
    private String attackLogId;
    /**
     * 攻击方ELO
     */
    private Integer attackerElo;
    /**
     * 防守方ELO
     */
    private Integer defenderElo;
    /**
     * 面子收入
     */
    private BigDecimal respectGain;
    /**
     * 面子损失
     */
    private BigDecimal respectLoss;
    /**
     * 当前chain数
     */
    private Integer chain;
    /**
     * 是否被拦截
     */
    private Boolean isInterrupted;
    /**
     * 是否匿名
     */
    private Boolean isStealth;
    /**
     * 是否Raid
     */
    private Boolean isRaid;
    /**
     * 是否rw
     */
    private Boolean isRankedWar;
    /**
     * 公平战斗
     */
    private BigDecimal modifierFairFight;
    /**
     * 战争模式
     */
    private BigDecimal modifierWar;
    /**
     * 复仇
     */
    private BigDecimal modifierRetaliation;
    /**
     * 组队
     */
    private BigDecimal modifierGroup;
    /**
     * 海外
     */
    private BigDecimal modifierOversea;
    /**
     * Chain加成
     */
    private BigDecimal modifierChain;
    /**
     * Warlord
     */
    private BigDecimal modifierWarlord;
}