package pn.torn.goldeneye.constants.torn.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 帮派新闻类型枚举
 *
 * @author Bai
 * @version 0.4.0
 * @since 2025.08.07
 */
@AllArgsConstructor
@Getter
public enum TornFactionRoleTypeEnum {
    /**
     * 帮主
     */
    LEADER("leader"),
    /**
     * OC指挥官
     */
    OC_COMMANDER("oc_commander"),
    /**
     * 战争指挥官
     */
    WAR_COMMANDER("war_commander");

    private final String code;
}