package pn.torn.goldeneye.constants.torn.enums.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户状态枚举
 *
 * @author Bai
 * @version 1.2.5
 * @since 2026.01.21
 */
@AllArgsConstructor
@Getter
@Slf4j
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
    ABROAD("Abroad"),
    /**
     * 住院
     */
    HOSPITAL("Hospital"),
    /**
     * 监狱
     */
    JAIL("Jail");

    private final String code;

    /**
     * 是否为可执行OC的状态
     */
    public static boolean isOcExecutable(String stateCode) {
        return OKAY.getCode().equals(stateCode);
    }

    /**
     * 是否为非可执行OC状态
     */
    public static boolean isOcNotExecutable(String stateCode) {
        return !isOcExecutable(stateCode);
    }

    public static TornUserStatusEnum codeOf(String code) {
        for (TornUserStatusEnum value : values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }

        log.error("用户状态未识别, Code: {}", code);
        return null;
    }
}