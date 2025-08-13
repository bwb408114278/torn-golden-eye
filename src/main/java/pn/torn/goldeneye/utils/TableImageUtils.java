package pn.torn.goldeneye.utils;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.model.TableDataBO;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.*;

/**
 * 表格图片类
 *
 * @author Bai
 * @version 0.1.0
 * @since 2025.07.24
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class TableImageUtils {
    /**
     * 文本对齐方式枚举
     */
    public enum TextAlignment {
        LEFT,
        CENTER,
        RIGHT,
        DISPERSED
    }

    /**
     * 单元格合并信息类
     */
    @Getter
    @NoArgsConstructor
    public static class CellMerge {
        private int rowSpan = 1;
        private int colSpan = 1;

        public CellMerge(int rowSpan, int colSpan) {
            this.rowSpan = Math.max(1, rowSpan);
            this.colSpan = Math.max(1, colSpan);
        }

        public boolean isMerged() {
            return rowSpan > 1 || colSpan > 1;
        }
    }

    /**
     * 单元格样式配置
     */
    public static class CellStyle {
        private Font font;
        private Color textColor = Color.BLACK;
        private Color bgColor;
        private TextAlignment alignment = TextAlignment.CENTER;
        private int padding = 10;

        public CellStyle setFont(Font font) {
            this.font = font;
            return this;
        }

        public CellStyle setTextColor(Color textColor) {
            this.textColor = textColor;
            return this;
        }

        public CellStyle setBgColor(Color bgColor) {
            this.bgColor = bgColor;
            return this;
        }

        public CellStyle setAlignment(TextAlignment alignment) {
            this.alignment = alignment;
            return this;
        }

        public CellStyle setPadding(int padding) {
            this.padding = padding;
            return this;
        }
    }

    /**
     * 表格渲染配置类
     */
    public static class TableConfig {
        private final Map<Point, CellMerge> mergeMap = new HashMap<>();
        private final Map<Point, CellStyle> styleMap = new HashMap<>();
        private int defaultCellHeight = 40;
        private Font defaultFont = new Font("微软雅黑", Font.PLAIN, 14);
        private Color defaultBgColor = new Color(255, 255, 255);
        private Color borderColor = new Color(217, 217, 217);

        public TableConfig addMerge(int row, int col, int rowSpan, int colSpan) {
            mergeMap.put(new Point(col, row), new CellMerge(rowSpan, colSpan));
            return this;
        }

        public TableConfig setCellStyle(int row, int col, CellStyle style) {
            styleMap.put(new Point(col, row), style);
            return this;
        }

        public TableConfig setDefaultCellHeight(int height) {
            this.defaultCellHeight = height;
            return this;
        }

        public TableConfig setDefaultFont(Font font) {
            this.defaultFont = font;
            return this;
        }

        public TableConfig setDefaultBgColor(Color color) {
            this.defaultBgColor = color;
            return this;
        }

        public TableConfig setBorderColor(Color color) {
            this.borderColor = color;
            return this;
        }
    }

    /**
     * 默认渲染表格到Base64（不包含特殊配置）
     */
    public static String renderTableToBase64(TableDataBO table) {
        return renderTableToBase64(table.getTableData(), table.getTableConfig());
    }

    /**
     * 默认渲染表格到Base64（不包含特殊配置）
     */
    public static String renderTableToBase64(List<List<String>> tableData) {
        return renderTableToBase64(tableData, new TableConfig());
    }

    /**
     * 完整渲染方法（包含配置参数）
     */
    public static String renderTableToBase64(List<List<String>> tableData, TableConfig config) {
        BufferedImage image = createTableImage(tableData, config);
        return convertToBase64(image);
    }

    private static BufferedImage createTableImage(List<List<String>> tableData, TableConfig config) {
        // 0. 校验表格结构
        if (tableData.isEmpty()) throw new IllegalArgumentException("Table data is empty");
        final int rows = tableData.size();
        final int cols = tableData.get(0).size();

        // 创建临时Graphics获取字体尺寸
        BufferedImage tempImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D tempG = tempImage.createGraphics();
        tempG.dispose();

        // 1. 计算行高（考虑合并单元格和自定义样式）
        int[] rowHeights = new int[rows];
        for (int r = 0; r < rows; r++) {
            rowHeights[r] = config.defaultCellHeight;
            for (int c = 0; c < cols; c++) {
                // 跳过被合并的单元格
                if (isCellMergedFromLeft(config, r, c) || isCellMergedFromAbove(config, r, c)) {
                    continue;
                }

                CellStyle style = getCellStyle(config, r, c);
                Font cellFont = (style != null && style.font != null) ? style.font : config.defaultFont;

                // 获取实际字体尺寸
                tempG = tempImage.createGraphics();
                FontMetrics metrics = tempG.getFontMetrics(cellFont);
                tempG.dispose();

                // 计算内容所需高度
                String text = tableData.get(r).get(c);
                int textHeight = metrics.getHeight();
                int padding = (style != null) ? style.padding : 10;
                int requiredHeight = textHeight + 2 * padding;

                // 更新行高（取最大值）
                if (requiredHeight > rowHeights[r]) {
                    rowHeights[r] = requiredHeight;
                }
            }
        }

        // 2. 计算列宽（考虑合并单元格）
        int[] colWidths = new int[cols];
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                // 跳过被合并的单元格
                if (isCellMergedFromLeft(config, r, c) || isCellMergedFromAbove(config, r, c)) {
                    continue;
                }

                CellStyle style = getCellStyle(config, r, c);
                Font cellFont = (style != null && style.font != null) ? style.font : config.defaultFont;
                int padding = (style != null) ? style.padding : 10;

                // 获取实际字体尺寸
                tempG = tempImage.createGraphics();
                FontMetrics metrics = tempG.getFontMetrics(cellFont);
                tempG.dispose();

                String text = tableData.get(r).get(c);
                int textWidth = metrics.stringWidth(text);
                int requiredWidth = textWidth + 2 * padding;

                // 获取单元格合并信息
                CellMerge merge = getCellMerge(config, r, c);
                if (merge.colSpan > 1) {
                    // 合并单元格需要分配到多列
                    requiredWidth /= merge.colSpan;
                }

                // 更新列宽（取最大值）
                if (requiredWidth > colWidths[c]) {
                    colWidths[c] = requiredWidth;
                }
            }
        }

        // 3. 创建正式图片
        int totalWidth = Arrays.stream(colWidths).sum();
        int totalHeight = Arrays.stream(rowHeights).sum();
        BufferedImage image = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = image.createGraphics();
        try {
            configureGraphics(g, config, totalWidth, totalHeight);
            drawTable(g, tableData, colWidths, rowHeights, config);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * 配置图形参数
     */
    private static void configureGraphics(Graphics2D g, TableConfig config, int width, int height) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(config.defaultFont);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
    }

    /**
     * 绘制表格
     */
    private static void drawTable(Graphics2D g, List<List<String>> tableData,
                                  int[] colWidths, int[] rowHeights, TableConfig config) {
        int y = 0;
        for (int r = 0; r < tableData.size(); r++) {
            List<String> row = tableData.get(r);
            int x = 0;

            for (int c = 0; c < colWidths.length; c++) {
                // 跳过被合并的单元格
                if (isCellMergedFromLeft(config, r, c) || isCellMergedFromAbove(config, r, c)) {
                    x += colWidths[c];
                    continue;
                }

                // 获取单元格合并信息
                CellMerge merge = getCellMerge(config, r, c);
                int cellWidth = 0;
                int cellHeight = 0;

                // 计算合并后的单元格尺寸
                for (int i = 0; i < merge.colSpan; i++) {
                    if (c + i < colWidths.length) {
                        cellWidth += colWidths[c + i];
                    }
                }
                for (int i = 0; i < merge.rowSpan; i++) {
                    if (r + i < rowHeights.length) {
                        cellHeight += rowHeights[r + i];
                    }
                }

                // 获取单元格样式
                CellStyle style = getCellStyle(config, r, c);

                // 绘制单元格背景
                drawCellBackground(g, x, y, cellWidth, cellHeight, config, style, r);

                // 绘制单元格内容
                drawCellContent(g, row.get(c), x, y, cellWidth, cellHeight, config, style);

                // 绘制单元格边框
                g.setColor(config.borderColor);
                g.drawRect(x, y, cellWidth, cellHeight);

                x += colWidths[c];
            }
            y += rowHeights[r];
        }

        // 添加表格外边框
        g.setColor(config.borderColor);
        g.drawRect(0, 0, Arrays.stream(colWidths).sum() - 1, Arrays.stream(rowHeights).sum() - 1);
    }

    /**
     * 绘制单元格背景
     */
    private static void drawCellBackground(Graphics2D g, int x, int y, int width, int height,
                                           TableConfig config, CellStyle style, int row) {
        Color bgColor;

        if (style != null && style.bgColor != null) {
            bgColor = style.bgColor;
        } else if (row % 2 == 0) {
            bgColor = new Color(242, 242, 242);
        } else {
            bgColor = config.defaultBgColor;
        }

        if (bgColor != null) {
            g.setColor(bgColor);
            g.fillRect(x, y, width, height);
        }
    }

    /**
     * 绘制单元格内容
     */
    private static void drawCellContent(Graphics2D g, String text, int cellX, int cellY, int cellWidth, int cellHeight,
                                        TableConfig config, CellStyle style) {
        if (text == null || text.isEmpty()) return;

        // 应用单元格样式
        Font cellFont = (style != null && style.font != null) ? style.font : config.defaultFont;
        Color textColor = (style != null) ? style.textColor : Color.BLACK;
        int padding = (style != null) ? style.padding : 10;
        TextAlignment alignment = (style != null) ? style.alignment : TextAlignment.CENTER;

        g.setFont(cellFont);
        g.setColor(textColor);

        // 获取字体尺寸
        FontMetrics metrics = g.getFontMetrics();
        int textWidth = metrics.stringWidth(text);
        int textHeight = metrics.getHeight();

        // 计算文本位置
        int textX = cellX + padding;
        int textY = cellY + (cellHeight + metrics.getAscent() - metrics.getDescent()) / 2;

        // 处理文本过长
        int maxWidth = cellWidth - 2 * padding;
        if (textWidth > maxWidth) {
            text = truncateText(metrics, text, maxWidth);
            textWidth = metrics.stringWidth(text);
        }

        // 应用对齐方式
        switch (alignment) {
            case CENTER:
                textX = cellX + (cellWidth - textWidth) / 2;
                break;
            case RIGHT:
                textX = cellX + cellWidth - textWidth - padding;
                break;
            case DISPERSED:
                drawDispersedText(g, text, cellX + padding, textY, maxWidth, metrics);
                return;
            case LEFT:
            default:
                // 保持左对齐
        }

        g.drawString(text, textX, textY);
    }

    /**
     * 检查单元格是否被左侧合并
     */
    private static boolean isCellMergedFromLeft(TableConfig config, int row, int col) {
        if (col == 0) return false;
        for (int i = col - 1; i >= 0; i--) {
            CellMerge merge = getCellMerge(config, row, i);
            if (merge != null && i + merge.colSpan > col) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查单元格是否被上方合并
     */
    private static boolean isCellMergedFromAbove(TableConfig config, int row, int col) {
        if (row == 0) return false;
        for (int i = row - 1; i >= 0; i--) {
            CellMerge merge = getCellMerge(config, i, col);
            if (merge != null && i + merge.rowSpan > row) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取单元格合并信息
     */
    private static CellMerge getCellMerge(TableConfig config, int row, int col) {
        return config.mergeMap.getOrDefault(new Point(col, row), new CellMerge());
    }

    /**
     * 获取单元格样式
     */
    private static CellStyle getCellStyle(TableConfig config, int row, int col) {
        return config.styleMap.get(new Point(col, row));
    }

    /**
     * 绘制分散对齐文本
     */
    private static void drawDispersedText(Graphics2D g, String text, int startX, int baselineY, int maxWidth,
                                          FontMetrics metrics) {
        // 分割单词
        String[] words = text.trim().split("\\s+");
        if (words.length < 2) {
            g.drawString(text, startX, baselineY);
            return;
        }

        // 计算总单词宽度
        int wordsWidth = 0;
        for (String word : words) {
            wordsWidth += metrics.stringWidth(word);
        }

        // 计算可用空白宽度
        int totalSpace = maxWidth - wordsWidth;
        if (totalSpace <= 0) {
            g.drawString(text, startX, baselineY);
            return;
        }

        // 计算间隙和额外像素
        int gapCount = words.length - 1;
        int baseGapWidth = totalSpace / gapCount;
        int extraGaps = totalSpace % gapCount;

        // 绘制每个单词
        int currentX = startX;
        for (int i = 0; i < words.length; i++) {
            if (!words[i].isEmpty()) {
                g.drawString(words[i], currentX, baselineY);
                currentX += metrics.stringWidth(words[i]);
            }

            // 添加空格（最后一个单词后不加）
            if (i < words.length - 1) {
                int gap = baseGapWidth + (i < extraGaps ? 1 : 0);
                currentX += gap;
            }
        }
    }

    /**
     * 文本截断处理
     */
    private static String truncateText(FontMetrics metrics, String text, int maxWidth) {
        final String ellipsis = "...";
        int ellipsisWidth = metrics.stringWidth(ellipsis);

        int len = text.length();
        while (len > 0 && metrics.stringWidth(text.substring(0, len)) + ellipsisWidth > maxWidth) {
            len--;
        }
        return (len > 0 ? text.substring(0, len) : "") + ellipsis;
    }

    /**
     * 转换为Base64
     */
    private static String convertToBase64(BufferedImage image) {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", stream);
            return Base64.getEncoder().encodeToString(stream.toByteArray());
        } catch (Exception e) {
            throw new BizException("图片转Base64异常", e);
        }
    }
}