package pn.torn.goldeneye.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 数字工具类
 *
 * @author Bai
 * @version 0.3.0
 * @since 2025.07.24
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class NumberUtils {
    public static final DecimalFormat THOUSAND_DELIMITER = new DecimalFormat("#,###");
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
     * 逗号分隔的字符串拆分为Long列表
     */
    public static List<Long> splitToLongList(String str) {
        if (!StringUtils.hasText(str)) {
            return List.of();
        }

        return Arrays.stream(str.split(",")).map(Long::parseLong).toList();
    }

    /**
     * 添加千位分隔符
     */
    public static String addDelimiters(long number) {
        return String.format("%,d", number);
    }

    /**
     * 添加千位分隔符
     */
    public static String addDelimiters(BigDecimal number) {
        DecimalFormat formatter = new DecimalFormat("#,##0.##");
        formatter.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.CHINA));
        return formatter.format(number);
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

    /**
     * 将大数字转换为k/m/b/t格式的字符串
     *
     * @param number 输入数字（long类型）
     * @return 格式化后的字符串（如1.5k, 2.34m, 3.78b, 4.56t）
     */
    public static String formatCompactNumber(long number) {
        // 处理负数
        if (number < 0) return "-" + formatCompactNumber(-number);

        // 处理0
        if (number == 0) return "0";

        // 定义单位和阈值
        final long TRILLION = 1_000_000_000_000L;
        final long BILLION = 1_000_000_000L;
        final long MILLION = 1_000_000L;
        final long THOUSAND = 1_000L;

        BigDecimal bigNum = BigDecimal.valueOf(number);

        if (bigNum.compareTo(BigDecimal.valueOf(TRILLION)) >= 0) {
            return formatToAbbr(bigNum, TRILLION, "t");
        } else if (bigNum.compareTo(BigDecimal.valueOf(BILLION)) >= 0) {
            return formatToAbbr(bigNum, BILLION, "b");
        } else if (bigNum.compareTo(BigDecimal.valueOf(MILLION)) >= 0) {
            return formatToAbbr(bigNum, MILLION, "m");
        } else if (bigNum.compareTo(BigDecimal.valueOf(THOUSAND)) >= 0) {
            return formatToAbbr(bigNum, THOUSAND, "k");
        } else {
            // 小于1000直接返回整数形式
            return String.valueOf(number);
        }
    }

    /**
     * 格式化为带后缀缩写
     *
     * @param number  数字
     * @param divisor 除数
     * @param suffix  后缀
     * @return 带后缀缩写
     */
    private static String formatToAbbr(BigDecimal number, long divisor, String suffix) {
        // 使用BigDecimal进行精确除法运算
        BigDecimal divided = number.divide(BigDecimal.valueOf(divisor), 4, RoundingMode.HALF_UP);

        // 检查是否需要进位到更高单位
        if (divided.compareTo(BigDecimal.valueOf(1000)) >= 0) {
            // 递归调用自身进位到更高单位
            return formatToAbbr(number, divisor * 1000, getNextSuffix(suffix));
        }

        // 保留两位小数并去除尾部零
        divided = divided.setScale(2, RoundingMode.HALF_UP);
        String result = divided.stripTrailingZeros().toPlainString();

        return result + suffix;
    }

    /**
     * 获取下一级数字后缀
     *
     * @param suffix 后缀
     * @return 下一级后缀
     */
    private static String getNextSuffix(String suffix) {
        return switch (suffix) {
            case "k" -> "m";
            case "m" -> "b";
            case "b" -> "t";
            default -> "t";  // t已是最高单位
        };
    }
}