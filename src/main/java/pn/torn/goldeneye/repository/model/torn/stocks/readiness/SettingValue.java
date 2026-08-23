package pn.torn.goldeneye.repository.model.torn.stocks.readiness;

/**
 * 系统设置只读值。
 *
 * @param settingKey   设置Key
 * @param settingValue 设置值
 * @author Bai
 * @version 1.4.2
 * @since 2026.08.23
 */
public record SettingValue(
        String settingKey,
        String settingValue) {
}
