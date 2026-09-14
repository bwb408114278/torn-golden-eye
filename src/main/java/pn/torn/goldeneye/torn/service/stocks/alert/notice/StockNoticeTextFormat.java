package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 股票通知文案共享格式化工具 - 旧版BUY/SELL与Alpha BUY/SELL的唯一格式化实现。
 *
 * <p>旧版组合通知与α换仓通知共用同一套价格、净收益率、持有时长与跟随截止时间格式,
 * 避免在两套渲染路径中复制格式化逻辑导致同一事实出现两种文本。本类无状态、无DAO依赖,
 * 方法体由{@code StockNoticeComposeService}的既有私有方法原样迁移,输出文本逐字不变。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.09
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StockNoticeTextFormat {
    /**
     * 百分比缩放系数(netReturn × 100 转为百分数)
     */
    private static final BigDecimal PERCENT_SCALE = new BigDecimal("100");
    /**
     * 百分比保留小数位
     */
    private static final int PERCENT_SCALE_DIGITS = 2;
    /**
     * 价格保留小数位
     */
    private static final int PRICE_SCALE_DIGITS = 2;
    /**
     * 跟随截止时间与预期成交时间格式(yyyy-MM-dd HH:mm)
     */
    private static final DateTimeFormatter FOLLOW_UNTIL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /**
     * 小时换算分钟
     */
    private static final long MINUTES_PER_HOUR = 60L;
    /**
     * 天换算小时
     */
    private static final long HOURS_PER_DAY = 24L;
    /**
     * 正数符号前缀
     */
    private static final String POSITIVE_SIGN = "+";

    /**
     * 格式化价格为保留 {@value #PRICE_SCALE_DIGITS} 位小数的字符串。
     *
     * @param price 价格,可为空
     * @return 格式化后的价格文本;入参为null时返回"0.00"
     */
    public static String formatPrice(BigDecimal price) {
        if (price == null) {
            return "0.00";
        }
        return price.setScale(PRICE_SCALE_DIGITS, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 格式化净收益率为 +0.80% 或 -1.50% 形式。
     * <p>
     * 入参为null时返回占位文本。正数前加"+",负数自带"-"。
     * 计算方式: netReturn × 100, 保留 {@value #PERCENT_SCALE_DIGITS} 位小数。
     *
     * @param netReturn 净收益率(小数形式,如0.008表示0.8%),可为空
     * @return 格式化后的百分比文本;入参为null时返回"未知"
     */
    public static String formatNetReturn(BigDecimal netReturn) {
        if (netReturn == null) {
            return "未知";
        }
        BigDecimal percent = netReturn.multiply(PERCENT_SCALE)
                .setScale(PERCENT_SCALE_DIGITS, RoundingMode.HALF_UP);
        String formatted = percent.abs().toPlainString();
        String sign = percent.signum() >= 0 ? POSITIVE_SIGN : "-";
        return sign + formatted + "%";
    }

    /**
     * 格式化持有时间为"X天Y小时"。
     * <p>
     * 入场或出场时间为空时返回占位文本;出场时间早于入场时间时返回占位文本。
     *
     * @param entryTime 入场时间,可为空
     * @param exitTime  出场时间,可为空
     * @return 格式化后的持有时间文本;缺失或时间倒置时返回"未知"
     */
    public static String formatHoldDuration(LocalDateTime entryTime, LocalDateTime exitTime) {
        if (entryTime == null || exitTime == null || exitTime.isBefore(entryTime)) {
            return "未知";
        }
        long totalMinutes = Duration.between(entryTime, exitTime).toMinutes();
        long totalHours = totalMinutes / MINUTES_PER_HOUR;
        long days = totalHours / HOURS_PER_DAY;
        long hours = totalHours % HOURS_PER_DAY;
        return days + "天" + hours + "小时";
    }

    /**
     * 格式化跟随截止时间或预期成交时间为 yyyy-MM-dd HH:mm。
     *
     * @param followUntil 跟随截止时间或预期成交时间,可为空
     * @return 格式化后的时间文本;入参为null时返回"未知"
     */
    public static String formatFollowUntil(LocalDateTime followUntil) {
        return followUntil == null ? "未知" : followUntil.format(FOLLOW_UNTIL_FORMATTER);
    }

    /**
     * 返回null安全的文本值。
     *
     * @param text 原始文本,可为空
     * @return 非null文本;入参为null时返回空字符串
     */
    public static String nullSafeText(String text) {
        return text == null ? "" : text;
    }
}
