package pn.torn.goldeneye.torn.model.faction.attack;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 帮派攻击响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Data
public class TornFactionAttackVO {
    /**
     * 攻击记录ID
     */
    private Long id;
    /**
     * 攻击日志Code
     */
    private String code;
    /**
     * 开始时间
     */
    private Long started;
    /**
     * 结束时间
     */
    private Long ended;
    /**
     * 攻方
     */
    private TornFactionAttackUserVO attacker;
    /**
     * 守方
     */
    private TornFactionAttackUserVO defender;
    /**
     * 战斗结果
     */
    private String result;
    /**
     * 获得面子
     */
    @JsonProperty("respect_gain")
    private BigDecimal respectGain;
    /**
     * 损失面子
     */
    @JsonProperty("respect_loss")
    private BigDecimal respectLoss;
    /**
     * 当前chain数
     */
    private Integer chain;
    /**
     * 是否被拦截
     */
    @JsonProperty("is_interrupted")
    private Boolean isInterrupted;
    /**
     * 是否匿名
     */
    @JsonProperty("is_stealthed")
    private Boolean isStealth;
    /**
     * 是否Raid
     */
    @JsonProperty("is_raid")
    private Boolean isRaid;
    /**
     * 是否rw
     */
    @JsonProperty("is_ranked_war")
    private Boolean isRankedWar;
    /**
     * 加成内容
     */
    private TornFactionAttackModifierVO modifiers;
}