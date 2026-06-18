package pn.torn.goldeneye.repository.model.faction.revive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RW复活记录
 *
 * @author Bai
 * @version 1.2.3
 * @since 2026.06.17
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("torn_faction_rw_revive")
public class TornFactionRwReviveDO extends BaseDO {
    /**
     * 主键ID
     */
    private Long id;
    /**
     * RW ID
     */
    private Long rwId;
    /**
     * 被复活目标所属帮派ID
     */
    private Long factionId;
    /**
     * 复活者ID
     */
    private Long reviverId;
    /**
     * 复活者昵称
     */
    private String reviverName;
    /**
     * 复活者帮派ID
     */
    private Long reviverFactionId;
    /**
     * 复活者帮派名
     */
    private String reviverFactionName;
    /**
     * 复活技能
     */
    private BigDecimal skill;
    /**
     * 被复活目标ID
     */
    private Long targetId;
    /**
     * 被复活目标昵称
     */
    private String targetName;
    /**
     * 被复活目标帮派ID
     */
    private Long targetFactionId;
    /**
     * 被复活目标帮派名
     */
    private String targetFactionName;
    /**
     * 成功率
     */
    private BigDecimal successChance;
    /**
     * 是否成功
     */
    private Boolean success;
    /**
     * 复活时间
     */
    private LocalDateTime reviveTime;
    /**
     * 目标当前血量
     */
    private Integer targetLifeCurrent;
    /**
     * 目标最大血量
     */
    private Integer targetLifeMaximum;
    /**
     * 回血量
     */
    private Integer healAmount;
}
