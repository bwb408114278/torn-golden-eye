package pn.torn.goldeneye.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * 时间工具类
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.07.29
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class DateTimeUtils {
    public static final DateTimeFormatter YEAR_MONTH_FORMATTER;
    private static final DateTimeFormatter DATE_FORMATTER;
    private static final DateTimeFormatter DATE_TIME_FORMATTER;

    static {
        DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");
    }

    /**
     * 转换为字符串
     */
    public static String convertToString(LocalDate date) {
        return date.format(DATE_FORMATTER);
    }

    /**
     * 转换为字符串
     */
    public static String convertToString(LocalDateTime datetime) {
        return datetime.format(DATE_TIME_FORMATTER);
    }

    /**
     * 转换时间戳为日期时间
     */
    public static LocalDateTime convertToDateTime(Long timestamp) {
        if (timestamp == null) {
            return null;
        }

        final boolean isSeconds = String.valueOf(timestamp).length() <= 10;
        long adjustedTimestamp = isSeconds ? timestamp * 1000 : timestamp;
        Instant instant = Instant.ofEpochMilli(adjustedTimestamp);
        return convertLocalTime(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * 转换字符串为日期时间
     */
    public static LocalDateTime convertToDateTime(String datetime) {
        return LocalDateTime.parse(datetime, DATE_TIME_FORMATTER);
    }

    /**
     * 转换字符串为日期
     */
    public static LocalDate convertToDate(String datetime) {
        return LocalDate.parse(datetime, DATE_FORMATTER);
    }

    /**
     * 转换为Instant
     */
    public static Instant convertToInstant(LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * 转转为短时间戳
     */
    public static Integer convertToShortTimestamp(LocalDateTime dateTime) {
        return Integer.valueOf(String.valueOf(convertToTimestamp(dateTime) / 1000));
    }

    /**
     * 转转为时间戳
     */
    public static Long convertToTimestamp(LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 转换为当前时区
     */
    public static LocalDateTime convertLocalTime(LocalDateTime dateTime) {
        return dateTime.plusHours(8L);
    }

    /**
     * 获取Torn的当前日期
     */
    public static LocalDate getTornLocalDate() {
        LocalDateTime result = LocalDateTime.now();
        boolean isTornOldDay = result.toLocalTime().isAfter(LocalTime.of(0, 0))
                && result.toLocalTime().isBefore(LocalTime.of(8, 0));
        return isTornOldDay ? result.toLocalDate().minusDays(1) : result.toLocalDate();
    }

    /**
     * 至少大于目标区间
     *
     * @param timestamp 时间戳1
     * @param dateTime  时间2
     * @param length    区间长度
     * @param unit      时间单位
     * @return true为大于
     */
    public static boolean isIntervalAtLeast(long timestamp, LocalDateTime dateTime, long length, TimeUnit unit) {
        LocalDateTime targetDateTime = convertToDateTime(timestamp);
        long diffNanos = Math.abs(ChronoUnit.NANOS.between(dateTime, targetDateTime));
        return diffNanos > unit.toNanos(length);
    }
}