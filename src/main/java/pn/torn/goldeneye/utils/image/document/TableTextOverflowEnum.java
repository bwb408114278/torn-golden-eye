package pn.torn.goldeneye.utils.image.document;

/**
 * 表格单元格文字溢出策略。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
public enum TableTextOverflowEnum {
    /**
     * 自动换行。
     */
    WRAP,
    /**
     * 单行省略。
     */
    ELLIPSIS,
    /**
     * 单行裁剪。
     */
    CLIP
}
