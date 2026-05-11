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
    DRUG(1),
    /**
     * 能量提醒
     */
    ENERGY(2),
    /**
     * 勇气提醒
     */
    NERVE(4),
    /**
     * Refill提醒
     */
    REFILL(8),
    /**
     * 旅行提醒
     */
    TRAVEL(16),
    /**
     * Booster CD提醒
     */
    BOOSTER(32),
    /**
     * 赛车提醒
     */
    RACING(64);

    public final int bit;

    public static VipNoticeTypeEnum bitOf(int bit) {
        for (VipNoticeTypeEnum value : values()) {
            if (value.getBit() == bit) {
                return value;
            }
        }

        throw new BizException("VIP提醒类型未识别位掩码: " + bit);
    }
}