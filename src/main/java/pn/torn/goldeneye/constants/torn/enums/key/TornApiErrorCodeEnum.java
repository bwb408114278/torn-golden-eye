package pn.torn.goldeneye.constants.torn.enums.key;

import lombok.Getter;

/**
 * Torn API 错误码枚举
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.03.06
 */
@Getter
public enum TornApiErrorCodeEnum {
    INVALID_KEY(2, "Invalid API key", ErrorLevel.WARN, true),
    TOO_MANY_REQUESTS(5, "Too many requests", ErrorLevel.ERROR, false),
    KEY_OWNER_FJ(10, "Key owner in federal jail", ErrorLevel.WARN, true),
    KEY_OWNER_INACTIVE(13, "Key owner is inactive", ErrorLevel.WARN, true),
    KEY_PAUSED(18, "API key paused", ErrorLevel.WARN, true),
    UNKNOWN(-1, "Unknown error", ErrorLevel.ERROR, false);

    private final int code;
    private final String message;
    private final ErrorLevel level;
    private final boolean shouldInvalidateKey;

    TornApiErrorCodeEnum(int code, String message, ErrorLevel level, boolean shouldInvalidateKey) {
        this.code = code;
        this.message = message;
        this.level = level;
        this.shouldInvalidateKey = shouldInvalidateKey;
    }

    public static TornApiErrorCodeEnum fromCode(int code) {
        return switch (code) {
            case 2 -> INVALID_KEY;
            case 5 -> TOO_MANY_REQUESTS;
            case 10 -> KEY_OWNER_FJ;
            case 13 -> KEY_OWNER_INACTIVE;
            case 18 -> KEY_PAUSED;
            default -> UNKNOWN;
        };
    }

    public enum ErrorLevel {
        WARN, ERROR
    }
}