package pn.torn.goldeneye.constants.torn.enums.user;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户日类类型枚举
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.30
 */
@AllArgsConstructor
@Getter
public enum TornUserLogTypeEnum {
    /**
     * 收到道具
     */
    ITEM_RECEIVE(4103);

    private final int code;
}