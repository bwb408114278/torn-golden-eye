package pn.torn.goldeneye.constants.torn.enums.user;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 旅行目的地枚举
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.21
 */
@AllArgsConstructor
@Getter
public enum TornTravelTargetEnum {
    /**
     * 墨西哥
     */
    MEXICO("Mexico", "墨西哥", 18, 13, 8),
    /**
     * 开曼
     */
    CAYMAN_ISLANDS("Cayman Islands", "开曼", 25, 18, 11),
    /**
     * 加拿大
     */
    CANADA("Canada", "加拿大", 29, 20, 12),
    /**
     * 夏威夷
     */
    HAWAII("Hawaii", "夏威夷", 94, 67, 40),
    /**
     * 英国
     */
    UNITED_KINGDOM("United Kingdom", "英国", 111, 80, 48),
    /**
     * 阿根廷
     */
    ARGENTINA("Argentina", "阿根廷", 117, 83, 50),
    /**
     * 瑞士
     */
    SWITZERLAND("Switzerland", "瑞士", 123, 88, 53),
    /**
     * 日本
     */
    JAPAN("Japan", "日本", 158, 113, 68),
    /**
     * 中国
     */
    CHINA("China", "中国", 169, 121, 72),
    /**
     * UAE
     */
    UAE("UAE", "UAE", 190, 135, 81),
    /**
     * 南非
     */
    SOUTH_AFRICA("South Africa", "南非", 208, 149, 89);

    private final String code;
    private final String name;
    private final long airstripMinutes;
    private final long privateMinutes;
    private final long businessMinutes;

    public static TornTravelTargetEnum textContain(String text) {
        for (TornTravelTargetEnum value : values()) {
            if (text.contains(value.getCode())) {
                return value;
            }
        }

        return null;
    }
}