package pn.torn.goldeneye.constants.torn.enums.user;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户状态枚举
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.21
 */
@AllArgsConstructor
@Getter
public enum TornUserStatusEnum {
    /**
     * 正常
     */
    OKAY("Okay"),
    /**
     * 旅行中
     */
    TRAVELING("Traveling"),
    /**
     * 滞留
     */
    ABROAD("Abroad");

    private final String code;
}