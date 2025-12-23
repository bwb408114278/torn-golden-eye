package pn.torn.goldeneye.torn.model.faction.attack;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 帮派攻击加成响应参数
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.12.18
 */
@Data
public class TornFactionAttackModifierVO {
    /**
     * 公平战斗
     */
    @JsonProperty("fair_fight")
    private BigDecimal fairFight;
    /**
     * 战争模式
     */
    private BigDecimal war;
    /**
     * 复仇
     */
    private BigDecimal retaliation;
    /**
     * 组队
     */
    private BigDecimal group;
    /**
     * 海外
     */
    private BigDecimal overseas;
    /**
     * Chain
     */
    private BigDecimal chain;
    /**
     * Warlord
     */
    private BigDecimal warlord;
}