package pn.torn.goldeneye.constants.torn.enums.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import pn.torn.goldeneye.base.exception.BizException;

/**
 * 旅行状态枚举
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.21
 */
@AllArgsConstructor
@Getter
public enum TornTravelStatusEnum {
    /**
     * 旅行中
     */
    TRAVELING("Traveling", "起飞"),
    /**
     * 返回中
     */
    RETURNING("Returning", "返回"),
    /**
     * 滞留
     */
    IN("In", "滞留");

    private final String code;
    private final String name;

    public static TornTravelStatusEnum codeOf(String code) {
        for (TornTravelStatusEnum value : values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }

        throw new BizException("飞行状态未识别Code: " + code);
    }

    public static TornTravelStatusEnum textStart(String text) {
        for (TornTravelStatusEnum value : values()) {
            if (value.getCode().startsWith(text)) {
                return value;
            }
        }

        return null;
    }
}