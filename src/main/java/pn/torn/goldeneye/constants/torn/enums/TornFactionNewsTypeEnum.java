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
public enum TornFactionNewsTypeEnum {
    /**
     * 攻击
     */
    ATTACK("attack"),
    /**
     * 仓库操作
     */
    ARMORY_ACTION("armoryAction"),
    /**
     * 取钱
     */
    GIVE_FUNDS("giveFunds");

    private final String code;
}