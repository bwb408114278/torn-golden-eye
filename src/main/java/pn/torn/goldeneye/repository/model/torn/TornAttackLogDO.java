package pn.torn.goldeneye.repository.model.torn;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.time.LocalDateTime;

/**
 * Torn攻击日志表
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_attack_log", autoResultMap = true)
public class TornAttackLogDO extends BaseDO {
    /**
     * ID
     */
    private Long id;
    /**
     * 日志ID
     */
    private String logId;
    /**
     * 日志时间
     */
    private LocalDateTime logTime;
    /**
     * 日志文本
     */
    private String logText;
    /**
     * 发生动作
     */
    private String logAction;
    /**
     * 图标
     */
    private String logIcon;
    /**
     * 攻方ID
     */
    private Long attackerId;
    /**
     * 攻方昵称
     */
    private String attackerName;
    /**
     * 攻方物品ID
     */
    private Long attackerItemId;
    /**
     * 攻方物品名称
     */
    private String attackerItemName;
    /**
     * 防守方ID
     */
    private Long defenderId;
    /**
     * 防守方昵称
     */
    private String defenderName;
    /**
     * 伤害
     */
    private Integer damage;
    /**
     * 是否失手
     */
    private Boolean isMiss;
    /**
     * 是否暴击
     */
    private Boolean isCritical;
    /**
     * 命中部位
     */
    private String hitLocation;
    /**
     * 子弹类型
     */
    private String ammoType;
    /**
     * 伤害类型
     */
    private String damageType;
    /**
     * 打针类型
     */
    private String syringeType;
    /**
     * 进攻方ELO
     */
    private Integer attackerElo;
    /**
     * 防守方ELO
     */
    private Integer defenderElo;
}