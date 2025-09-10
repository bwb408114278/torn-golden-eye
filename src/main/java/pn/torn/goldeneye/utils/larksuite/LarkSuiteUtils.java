package pn.torn.goldeneye.utils.larksuite;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 飞书工具类
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.09
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class LarkSuiteUtils {
    /**
     * 安全地从飞书字段中提取文本值
     * <p>
     * 兼容简单字符串和飞书特有的 rich text 格式 (e.g., [{"text":"...","type":"text"}])
     * </p>
     */
    @SuppressWarnings("unchecked")
    public static String getTextFieldValue(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return "";
        }
        if (value instanceof String str) {
            return str;
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            Object firstElement = list.get(0);
            if (firstElement instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) firstElement;
                return Objects.toString(map.get("text"), "");
            }
        }
        return value.toString();
    }
}