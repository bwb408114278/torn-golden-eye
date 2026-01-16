package pn.torn.goldeneye.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 字符串工具类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.01.16
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class CharacterUtils {
    /**
     * 首字母大写，其余字母小写
     */
    public static String capitalFirstLetter(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
}