package pn.torn.goldeneye.repository.model.faction.attack;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import pn.torn.goldeneye.repository.model.BaseDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;


/**
 * 帮派攻击新闻表
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.17
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "torn_faction_attack_news", autoResultMap = true)
public class TornFactionAttackNewsDO extends BaseDO {
    /**
     * ID
     */
    private String id;
    /**
     * 帮派ID
     */
    private Long factionId;
    /**
     * 攻方用户ID
     */
    private Long attackUserId;
    /**
     * 攻方用户昵称
     */
    private String attackUserNickname;
    /**
     * 守方用户ID
     */
    private Long defendUserId;
    /**
     * 守方用户昵称
     */
    private String defendUserNickname;
    /**
     * 攻击时间
     */
    private LocalDateTime attackTime;
    /**
     * 攻击结果
     */
    private String attackResult;
    /**
     * 攻击日志ID
     */
    private String attackLogId;
    /**
     * 面子变动
     */
    private BigDecimal respectChange;
}