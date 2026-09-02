package pn.torn.goldeneye.utils.image.document;

import java.util.List;
import java.util.Objects;

/**
 * 不可变表格单元格值对象。
 *
 * @param content  单元格受控内容，不能为null
 * @param style    单元格语义样式
 * @param rowSpan  跨行数量，必须大于0
 * @param colSpan  跨列数量，必须大于0
 * @param overflow 文字溢出策略
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
public record TableCell(
        TableCellContent content,
        TableCellStyleEnum style,
        int rowSpan,
        int colSpan,
        TableTextOverflowEnum overflow) {

    /**
     * 创建并校验单元格值对象。
     */
    public TableCell {
        Objects.requireNonNull(content, "content不能为null");
        Objects.requireNonNull(style, "style不能为null");
        Objects.requireNonNull(overflow, "overflow不能为null");
        if (rowSpan <= 0) {
            throw new IllegalArgumentException("rowSpan必须大于0");
        }
        if (colSpan <= 0) {
            throw new IllegalArgumentException("colSpan必须大于0");
        }
    }

    /**
     * 纯文本兼容构造入口，统一转换为{@link TableCellContent.PlainText}。
     *
     * @param text     单元格业务文本，不能为null
     * @param style    单元格语义样式
     * @param rowSpan  跨行数量，必须大于0
     * @param colSpan  跨列数量，必须大于0
     * @param overflow 文字溢出策略
     */
    public TableCell(String text, TableCellStyleEnum style, int rowSpan, int colSpan,
                     TableTextOverflowEnum overflow) {
        this(new TableCellContent.PlainText(text), style, rowSpan, colSpan, overflow);
    }

    /**
     * 返回内容模型的可读组合文本，仅供兼容断言或日志使用；渲染必须读取{@link #content()}。
     *
     * @return 可读组合文本
     */
    public String text() {
        return content().readableText();
    }

    /**
     * 创建纯文本单元格。
     *
     * @param text     单元格业务文本，不能为null
     * @param style    单元格语义样式
     * @param rowSpan  跨行数量，必须大于0
     * @param colSpan  跨列数量，必须大于0
     * @param overflow 文字溢出策略
     * @return 纯文本单元格
     */
    public static TableCell plainText(String text, TableCellStyleEnum style, int rowSpan, int colSpan,
                                      TableTextOverflowEnum overflow) {
        return new TableCell(text, style, rowSpan, colSpan, overflow);
    }

    /**
     * 创建主文本加单个状态徽章单元格。
     *
     * @param primaryText 主文本，不能为null
     * @param badgeText   徽章文本，不能为null且不能为空白
     * @param badgeTone   徽章受控色调，不能为null
     * @param style       单元格语义样式
     * @param rowSpan     跨行数量，必须大于0
     * @param colSpan     跨列数量，必须大于0
     * @param overflow    文字溢出策略
     * @return 徽章单元格
     */
    public static TableCell badgeText(String primaryText, String badgeText, TableCellBadgeToneEnum badgeTone,
                                      TableCellStyleEnum style, int rowSpan, int colSpan,
                                      TableTextOverflowEnum overflow) {
        return badgeText(primaryText, List.of(new TableCellContent.Badge(badgeText, badgeTone)),
                style, rowSpan, colSpan, overflow);
    }

    /**
     * 创建主文本加多个状态徽章单元格。
     *
     * @param primaryText 主文本，不能为null
     * @param badges      状态徽章，不能为null且至少一个
     * @param style       单元格语义样式
     * @param rowSpan     跨行数量，必须大于0
     * @param colSpan     跨列数量，必须大于0
     * @param overflow    文字溢出策略
     * @return 徽章单元格
     */
    public static TableCell badgeText(String primaryText, List<TableCellContent.Badge> badges,
                                      TableCellStyleEnum style, int rowSpan, int colSpan,
                                      TableTextOverflowEnum overflow) {
        return new TableCell(new TableCellContent.BadgeText(primaryText, badges),
                style, rowSpan, colSpan, overflow);
    }

    /**
     * 创建左、中、右三段式单元格。
     *
     * @param leadingText  左侧文本，不能为null，允许为空字符串
     * @param centerText   中间文本，不能为null且不能为空白
     * @param trailingText 右侧文本，不能为null，允许为空字符串
     * @param style        单元格语义样式
     * @param rowSpan      跨行数量，必须大于0
     * @param colSpan      跨列数量，必须大于0
     * @param overflow     文字溢出策略
     * @return 三段式单元格
     */
    public static TableCell threePartText(String leadingText, String centerText, String trailingText,
                                          TableCellStyleEnum style, int rowSpan, int colSpan,
                                          TableTextOverflowEnum overflow) {
        return new TableCell(new TableCellContent.ThreePartText(leadingText, centerText, trailingText),
                style, rowSpan, colSpan, overflow);
    }
}
