package pn.torn.goldeneye.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.29
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class DateTimeUtils {
    /**
     * 转换为字符串
     */
    public static String convertToString(LocalDateTime datetime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return datetime.format(formatter);
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
     * 转换为Instant
     */
    public static Instant convertToInstant(LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * 转转为短时间戳
     */
    public static Integer convertToShortTimestamp(LocalDateTime dateTime) {
        return Long.valueOf(convertToTimestamp(dateTime) / 1000).intValue();
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
}