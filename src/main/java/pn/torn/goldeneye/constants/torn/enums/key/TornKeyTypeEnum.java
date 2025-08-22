package pn.torn.goldeneye.constants.torn.enums.key;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Api Key类型枚举
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.08.21
 */
@AllArgsConstructor
@Getter
public enum TornKeyTypeEnum {
    /**
     * Public
     */
    PUBLIC("Public Only", "PUBLIC"),
    /**
     * Minimal
     */
    MINIMAL("Minimal Access", "MINIMAL"),
    /**
     * Limited
     */
    LIMIT("Limited Access", "LIMIT"),
    /**
     * Full
     */
    FULL("Full Access", "FULL");

    private final String code;
    private final String shortCode;

    public static TornKeyTypeEnum codeOf(String code) {
        for (TornKeyTypeEnum value : values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }

        return null;
    }
}