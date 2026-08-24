package pn.torn.goldeneye.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * RW统计窗口业务序号转换工具。
 *
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RwStatWindowCodeUtils {
    private static final int ALPHABET_SIZE = 26;

    /**
     * 将正整数序号转换为Excel风格字母编码。
     *
     * @param sequence 正整数序号
     * @return 窗口字母编码
     * @throws IllegalArgumentException 序号不是正整数时抛出
     */
    public static String toCode(long sequence) {
        if (sequence <= 0) {
            throw new IllegalArgumentException("窗口序号必须为正整数");
        }
        StringBuilder code = new StringBuilder();
        long remaining = sequence;
        while (remaining > 0) {
            remaining--;
            code.append((char) ('A' + remaining % ALPHABET_SIZE));
            remaining /= ALPHABET_SIZE;
        }
        return code.reverse().toString();
    }

    /**
     * 将窗口字母编码转换为正整数序号。
     *
     * @param code 窗口字母编码
     * @return 正整数序号
     * @throws IllegalArgumentException 编码为空或包含非字母字符时抛出
     */
    public static long toSequence(String code) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("窗口编码不能为空");
        }
        long sequence = 0;
        for (char character : code.toUpperCase().toCharArray()) {
            if (character < 'A' || character > 'Z') {
                throw new IllegalArgumentException("窗口编码必须为英文字母");
            }
            if (sequence > (Long.MAX_VALUE - (character - 'A' + 1)) / ALPHABET_SIZE) {
                throw new IllegalArgumentException("窗口编码超出范围");
            }
            sequence = sequence * ALPHABET_SIZE + character - 'A' + 1;
        }
        return sequence;
    }
}
