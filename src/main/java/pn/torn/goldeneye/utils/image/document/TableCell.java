package pn.torn.goldeneye.utils.image.document;

import java.util.Objects;

/**
 * 不可变表格单元格值对象。
 *
 * @param text     单元格业务文本，可以为空但不能为null
 * @param style    单元格语义样式
 * @param rowSpan  跨行数量，必须大于0
 * @param colSpan  跨列数量，必须大于0
 * @param overflow 文字溢出策略
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
public record TableCell(
        String text,
        TableCellStyleEnum style,
        int rowSpan,
        int colSpan,
        TableTextOverflowEnum overflow) {

    /**
     * 创建并校验单元格值对象。
     */
    public TableCell {
        Objects.requireNonNull(text, "text不能为null");
        Objects.requireNonNull(style, "style不能为null");
        Objects.requireNonNull(overflow, "overflow不能为null");
        if (rowSpan <= 0) {
            throw new IllegalArgumentException("rowSpan必须大于0");
        }
        if (colSpan <= 0) {
            throw new IllegalArgumentException("colSpan必须大于0");
        }
    }
}
