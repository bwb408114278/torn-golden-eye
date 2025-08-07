package pn.torn.goldeneye.constants.torn.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 帮派新闻类型枚举
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.07
 */
@AllArgsConstructor
@Getter
public enum TornFactionNewsTypeEnum {
    /**
     * 仓库操作
     */
    ARMORY_ACTION("armoryAction");

    private final String code;
}