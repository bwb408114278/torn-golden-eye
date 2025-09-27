package pn.torn.goldeneye.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * 数字工具类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class NumberUtils {
    private static final Pattern VALID_PATTERN = Pattern.compile("^[-+]?\\d*\\.?\\d+\\s*[kmbtKMBT]?$");

    /**
     * 判断字符串是否为长整数
     *
     * @param str 字符串
     * @return true为是
     */
    public static boolean isLong(String str) {
        if (!StringUtils.hasText(str)) {
            return false;
        }

        try {
            Long.parseLong(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 判断字符串是否为整数
     *
     * @param str 字符串
     * @return true为是
     */
    public static boolean isInt(String str) {
        if (!StringUtils.hasText(str)) {
            return false;
        }

        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 字符串转换为Long
     *
     * @param input 字符串
     * @return Long
     */
    public static Long convert(String input) {
        // 验证整个输入格式是否有效
        if (!VALID_PATTERN.matcher(input).matches()) {
            return null;
        }

        // 提取数字部分
        String numberPart = input.replaceAll("[^0-9.+-]", "");
        if (numberPart.isEmpty()) {
            return null;
        }

        BigDecimal number;
        try {
            number = new BigDecimal(numberPart);
        } catch (NumberFormatException e) {
            return null;
        }

        String unit = input.replaceAll("[^kmbtKMBT]", "").toLowerCase();
        return switch (unit) {
            case "k" -> number.multiply(BigDecimal.valueOf(1_000))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            case "m" -> number.multiply(BigDecimal.valueOf(1_000_000))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            case "b" -> number.multiply(BigDecimal.valueOf(1_000_000_000))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            case "t" -> number.multiply(BigDecimal.valueOf(1_000_000_000_000L))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            default -> number.setScale(0, RoundingMode.HALF_UP).longValueExact();
        };
    }
}