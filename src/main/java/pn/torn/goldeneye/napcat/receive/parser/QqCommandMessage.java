package pn.torn.goldeneye.napcat.receive.parser;

/**
 * NapCat 入站命令解析结果。
 *
 * <p>{@code commandText} 为消息中所有 text 段按原始顺序拼接后的纯文本命令，
 * 不包含 at 段；存在 at 时会忽略 NapCat 自动生成的尾部空白并 trim 命令文本。
 * {@code atMarker} 为解析层生成的受控 at 目标标记，仅当消息中存在 at 时非空。
 * 标记使用控制字符作为边界，普通文本消息无法伪造。</p>
 *
 * @param commandText 纯文本命令；无 at 时保持原始 text 段内容
 * @param atMarker    内部 at 目标标记；无 at 时为空字符串
 * @author Bai
 * @version 1.4.0
 * @since 2026.08.21
 */
public record QqCommandMessage(
        String commandText,
        String atMarker) {

    /**
     * 内部 at 标记前缀。
     */
    public static final String AT_MARKER_PREFIX = "\u0000AT:";

    /**
     * 内部 at 标记后缀。
     */
    public static final String AT_MARKER_SUFFIX = "\u0000";

    /**
     * 非法 at 标记（多个 at、非法 QQ、超出 long 范围等）。
     */
    public static final String INVALID_AT_MARKER = "\u0000INVALID_AT\u0000";

    /**
     * 生成合法 at 目标标记。
     *
     * @param qq 已校验的 QQ 号
     * @return 内部 at 标记
     */
    public static String buildAtMarker(long qq) {
        return AT_MARKER_PREFIX + qq + AT_MARKER_SUFFIX;
    }

    /**
     * 是否包含 at 目标标记。
     *
     * @return true 表示消息中存在 at 段
     */
    public boolean hasAt() {
        return !atMarker.isEmpty();
    }
}
