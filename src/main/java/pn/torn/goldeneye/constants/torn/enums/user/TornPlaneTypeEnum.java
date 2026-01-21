package pn.torn.goldeneye.constants.torn.enums.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import pn.torn.goldeneye.base.exception.BizException;

/**
 * 飞机类型枚举
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.21
 */
@AllArgsConstructor
@Getter
public enum TornPlaneTypeEnum {
    /**
     * 标准
     */
    STANDARD("Standard", ""),
    /**
     * PI
     */
    AIRSTRIP("Airstrip", "light_aircraft"),
    /**
     * WLT
     */
    PRIVATE("Private", "private_jet"),
    /**
     * 机票
     */
    BUSINESS("Business", "airliner");

    private final String code;
    private final String imageType;

    public static TornPlaneTypeEnum codeOf(String code) {
        for (TornPlaneTypeEnum value : values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }

        throw new BizException("飞机类型未识别Code: " + code);
    }

    public static String imageOfCode(String imageType) {
        for (TornPlaneTypeEnum value : values()) {
            if (value.getImageType().equals(imageType)) {
                return imageType;
            }
        }

        return "";
    }
}