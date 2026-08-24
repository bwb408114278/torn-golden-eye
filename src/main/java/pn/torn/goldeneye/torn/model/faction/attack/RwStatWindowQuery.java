package pn.torn.goldeneye.torn.model.faction.attack;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * RW统计窗口查询参数。
 *
 * @param rwId 指定的RW ID，未指定时为null
 * @param windowCode 指定的窗口字母，未指定时为null
 * @author Bai
 * @version 1.4.4
 * @since 2026.08.24
 */
public record RwStatWindowQuery(Long rwId, String windowCode) {
    private static final Pattern WINDOW_CODE_PATTERN = Pattern.compile("[A-Za-z]+");
    private static final String INVALID_PARAMETER_MESSAGE = "参数有误";

    /**
     * 解析RW统计指令参数。
     *
     * @param text 指令参数
     * @return 解析后的查询参数
     * @throws IllegalArgumentException 参数格式不合法时抛出
     */
    public static RwStatWindowQuery parse(String text) {
        if (text == null || text.isEmpty()) {
            return new RwStatWindowQuery(null, null);
        }
        String[] parts = text.split("#", -1);
        if (parts.length == 1) {
            return parseSinglePart(parts[0]);
        }
        if (parts.length == 2) {
            return parseRwAndWindow(parts[0], parts[1]);
        }
        throw invalidParameter();
    }

    /**
     * 解析单段RWID或窗口编码参数。
     *
     * @param part 单段参数
     * @return 解析后的查询参数
     */
    private static RwStatWindowQuery parseSinglePart(String part) {
        if (isPositiveLong(part)) {
            return new RwStatWindowQuery(Long.parseLong(part), null);
        }
        if (isWindowCode(part)) {
            return new RwStatWindowQuery(null, normalizeWindowCode(part));
        }
        throw invalidParameter();
    }

    private static RwStatWindowQuery parseRwAndWindow(String rwIdPart, String windowPart) {
        if (!isPositiveLong(rwIdPart) || !isWindowCode(windowPart)) {
            throw invalidParameter();
        }
        return new RwStatWindowQuery(Long.parseLong(rwIdPart), normalizeWindowCode(windowPart));
    }

    private static boolean isPositiveLong(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isWindowCode(String value) {
        return value != null && WINDOW_CODE_PATTERN.matcher(value).matches();
    }

    private static String normalizeWindowCode(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    private static IllegalArgumentException invalidParameter() {
        return new IllegalArgumentException(INVALID_PARAMETER_MESSAGE);
    }
}
