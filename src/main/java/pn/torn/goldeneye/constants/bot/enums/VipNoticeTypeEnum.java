package pn.torn.goldeneye.constants.bot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import pn.torn.goldeneye.base.exception.BizException;

/**
 * VIP订阅类型
 *
 * @author Bai
 * @version 1.1.1
 * @since 2026.05.11
 */
@Getter
@AllArgsConstructor
public enum VipNoticeTypeEnum {
    /**
     * Drug CD提醒
     */
    DRUG(1, "药"),
    /**
     * 能量提醒
     */
    ENERGY(2, "energy"),
    /**
     * 勇气提醒
     */
    NERVE(4, "nerve"),
    /**
     * Refill提醒
     */
    REFILL(8, "refill"),
    /**
     * 旅行提醒
     */
    TRAVEL(16, "躺飞"),
    /**
     * Booster CD提醒
     */
    BOOSTER(32, "booster");

    private final int bit;
    private final String alias;

    public static VipNoticeTypeEnum bitOf(int bit) {
        for (VipNoticeTypeEnum value : values()) {
            if (value.getBit() == bit) {
                return value;
            }
        }

        throw new BizException("VIP提醒类型未识别位掩码: " + bit);
    }

    public static VipNoticeTypeEnum aliasOf(String alias) {
        for (VipNoticeTypeEnum value : values()) {
            if (alias.trim().toLowerCase().equals(value.getAlias())) {
                return value;
            }
        }

        return null;
    }
}