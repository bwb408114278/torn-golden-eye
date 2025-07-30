package pn.torn.goldeneye.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

/**
 * 数字工具类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class NumberUtils {
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
}