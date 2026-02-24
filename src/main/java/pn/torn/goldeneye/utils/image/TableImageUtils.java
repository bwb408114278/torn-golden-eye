package pn.torn.goldeneye.utils.image;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.base.model.TableDataBO;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.List;

/**
 * 表格图片转换工具类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2025.07.24
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class TableImageUtils {
    /**
     * 文本对齐方式枚举
     */
    public enum TextAlignment {LEFT, CENTER, RIGHT, DISPERSED}

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
    }

    /**
     * 单元格样式配置
     */
    @Getter
    public static class CellStyle {
        private Font font;
        private Color textColor = Color.BLACK;
        private Color bgColor;
        private TextAlignment alignment = TextAlignment.CENTER;
        private int horizontalPadding = 10;
        private int verticalPadding = 10;
        private int lineSpacing = 20;

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
            this.horizontalPadding = padding;
            this.verticalPadding = padding;
            return this;
        }

        public CellStyle setHorizontalPadding(int padding) {
            this.horizontalPadding = padding;
            return this;
        }

        public CellStyle setVerticalPadding(int padding) {
            this.verticalPadding = padding;
            return this;
        }

        public CellStyle setLineSpacing(int spacing) {
            this.lineSpacing = spacing;
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
        private Color defaultBgColor = Color.WHITE;
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

        public TableConfig setSubTitle(int row, int columnSize) {
            CellStyle subTitleStyle = new CellStyle().setFont(new Font("微软雅黑", Font.BOLD, 16));
            for (int i = 0; i < columnSize; i++) {
                this.setCellStyle(row, i, subTitleStyle);
            }
            return this;
        }
    }

    /**
     * 渲染表格到Base64（不包含特殊配置）
     */
    public static String renderTableToBase64(TableDataBO table) {
        return renderTableToBase64(table.getTableData(), table.getTableConfig());
    }

    /**
     * 渲染表格到Base64（不包含特殊配置）
     */
    public static String renderTableToBase64(List<List<String>> tableData) {
        return renderTableToBase64(tableData, new TableConfig());
    }

    /**
     * 渲染表格到Base64
     */
    public static String renderTableToBase64(List<List<String>> tableData, TableConfig config) {
        if (tableData == null || tableData.isEmpty()) {
            throw new IllegalArgumentException("Table data cannot be empty");
        }
        TableRenderer renderer = new TableRenderer(tableData, config);
        BufferedImage image = renderer.render();
        return convertToBase64(image);
    }

    /**
     * 渲染表格到图片
     */
    public static BufferedImage renderTableToImage(List<List<String>> tableData, TableConfig config) {
        if (tableData == null || tableData.isEmpty()) {
            throw new IllegalArgumentException("Table data cannot be empty");
        }
        TableRenderer renderer = new TableRenderer(tableData, config);
        return renderer.render();
    }

    /**
     * 计算表格宽度
     */
    public static int calculateTableWidth(List<List<String>> tableData, TableConfig config) {
        if (tableData == null || tableData.isEmpty()) {
            throw new IllegalArgumentException("Table data cannot be empty");
        }
        TableRenderer renderer = new TableRenderer(tableData, config);
        renderer.initSkippedCells();
        renderer.calculateColWidths();
        return Arrays.stream(renderer.colWidths).sum();
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

    /**
     * 核心渲染上下文
     */
    private static class TableRenderer {
        private final List<List<String>> tableData;
        private final TableConfig config;
        private final int rows;
        private final int cols;
        private final int[] rowHeights;
        private final int[] colWidths;
        private final boolean[][] skippedCells;
        // 一个临时Graphics提升性能，避免循环内频繁创建
        private final Graphics2D tempG;

        public TableRenderer(List<List<String>> tableData, TableConfig config) {
            this.tableData = tableData;
            this.config = config;
            this.rows = tableData.size();
            this.cols = tableData.getFirst().size();
            this.rowHeights = new int[rows];
            this.colWidths = new int[cols];
            this.skippedCells = new boolean[rows][cols];
            BufferedImage tempImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            this.tempG = tempImg.createGraphics();
        }

        /**
         * 渲染主流程
         */
        public BufferedImage render() {
            try {
                Arrays.fill(rowHeights, config.defaultCellHeight);
                initSkippedCells();
                calculateRowHeights();
                calculateColWidths();
                return drawFinalImage();
            } finally {
                tempG.dispose();
            }
        }

        /**
         * 初始化跳过绘制的单元格
         */
        private void initSkippedCells() {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    CellMerge merge = getCellMerge(r, c);
                    if (merge.rowSpan > 1 || merge.colSpan > 1) {
                        markMergedCellsAsSkipped(r, c, merge);
                    }
                }
            }
        }

        /**
         * 标记合并的单元格为跳过
         */
        private void markMergedCellsAsSkipped(int r, int c, CellMerge merge) {
            for (int i = 0; i < merge.rowSpan; i++) {
                for (int j = 0; j < merge.colSpan; j++) {
                    if ((i != 0 || j != 0) && r + i < rows && c + j < cols) {
                        skippedCells[r + i][c + j] = true;
                    }
                }
            }
        }

        /**
         * 计算行高
         */
        private void calculateRowHeights() {
            int[] minRowHeights = new int[rows];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (!skippedCells[r][c]) {
                        updateMinRowHeights(minRowHeights, r, c);
                    }
                }
            }
            for (int r = 0; r < rows; r++) {
                rowHeights[r] = Math.max(rowHeights[r], minRowHeights[r]);
            }
        }

        /**
         * 更新最小行高
         */
        private void updateMinRowHeights(int[] minRowHeights, int r, int c) {
            int requiredHeight = calculateCellRequiredHeight(r, c);
            int rowSpan = getCellMerge(r, c).getRowSpan();
            if (rowSpan == 1) {
                minRowHeights[r] = Math.max(minRowHeights[r], requiredHeight);
                return;
            }
            int minHeightPerRow = (int) Math.ceil((double) requiredHeight / rowSpan);
            for (int i = 0; i < rowSpan && (r + i) < rows; i++) {
                minRowHeights[r + i] = Math.max(minRowHeights[r + i], minHeightPerRow);
            }
        }

        /**
         * 计算列宽
         */
        private void calculateColWidths() {
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) {
                    if (skippedCells[r][c]) continue;
                    int requiredWidth = calculateCellRequiredWidth(r, c);
                    CellMerge merge = getCellMerge(r, c);
                    if (merge.colSpan > 1) {
                        distributeColWidthDeficit(c, merge.colSpan, requiredWidth);
                    } else {
                        colWidths[c] = Math.max(colWidths[c], requiredWidth);
                    }
                }
            }
        }

        /**
         * 分配列宽差值
         */
        private void distributeColWidthDeficit(int startCol, int colSpan, int requiredWidth) {
            int currentTotalWidth = 0;
            for (int i = 0; i < colSpan && (startCol + i) < cols; i++) {
                currentTotalWidth += colWidths[startCol + i];
            }
            if (requiredWidth > currentTotalWidth) {
                int deficit = requiredWidth - currentTotalWidth;
                int extraPerCol = deficit / colSpan;
                int remainder = deficit % colSpan;
                for (int i = 0; i < colSpan && (startCol + i) < cols; i++) {
                    colWidths[startCol + i] += extraPerCol + (i < remainder ? 1 : 0);
                }
            }
        }

        /**
         * 计算单元格所需高度
         */
        private int calculateCellRequiredHeight(int r, int c) {
            CellStyle style = getCellStyle(r, c);
            FontMetrics metrics = tempG.getFontMetrics(getFont(style));
            String text = tableData.get(r).get(c);
            int lineCount = (text == null || text.isEmpty()) ? 1 : text.split("\n").length;
            int lineHeight = metrics.getHeight();
            int lineSpacing = (style != null) ? style.getLineSpacing() : 20;
            int padding = (style != null) ? style.getVerticalPadding() : 10;
            int textHeight = lineHeight * lineCount + lineSpacing * (lineCount - 1);
            return textHeight + 2 * padding;
        }

        /**
         * 计算单元格所需宽度
         */
        private int calculateCellRequiredWidth(int r, int c) {
            CellStyle style = getCellStyle(r, c);
            FontMetrics metrics = tempG.getFontMetrics(getFont(style));
            String text = tableData.get(r).get(c);
            int maxLineWidth = 0;
            if (text != null && !text.isEmpty()) {
                for (String line : text.split("\n")) {
                    maxLineWidth = Math.max(maxLineWidth, metrics.stringWidth(line));
                }
            }
            int padding = (style != null) ? style.getHorizontalPadding() : 10;
            return maxLineWidth + 2 * padding;
        }

        /**
         * 绘制最终图形
         */
        private BufferedImage drawFinalImage() {
            int totalWidth = Arrays.stream(colWidths).sum();
            int totalHeight = Arrays.stream(rowHeights).sum();
            BufferedImage image = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                configureGraphics(g, totalWidth, totalHeight);
                drawAllCells(g, totalWidth, totalHeight);
            } finally {
                g.dispose();
            }

            return image;
        }

        /**
         * 图片配置
         */
        private void configureGraphics(Graphics2D g, int width, int height) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(config.defaultFont);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
        }

        /**
         * 绘制所有单元格
         */
        private void drawAllCells(Graphics2D g, int totalWidth, int totalHeight) {
            int y = 0;
            for (int r = 0; r < rows; r++) {
                int x = 0;
                for (int c = 0; c < cols; c++) {
                    if (skippedCells[r][c]) {
                        x += colWidths[c];
                        continue;
                    }

                    CellMerge merge = getCellMerge(r, c);
                    int cellW = calculateSpanWidth(c, merge.colSpan);
                    int cellH = calculateSpanHeight(r, merge.rowSpan);
                    CellStyle style = getCellStyle(r, c);
                    String text = tableData.get(r).get(c);
                    drawCellBackground(g, r, x, y, cellW, cellH, style);
                    drawCellContent(g, text, x, y, cellW, cellH, style);
                    drawCellBorder(g, x, y, cellW, cellH);
                    x += colWidths[c];
                }
                y += rowHeights[r];
            }

            // 外边框
            g.setColor(config.borderColor);
            g.drawRect(0, 0, totalWidth - 1, totalHeight - 1);
        }

        /**
         * 绘制单元格背景色
         */
        private void drawCellBackground(Graphics2D g, int row, int x, int y, int w, int h, CellStyle style) {
            Color bgColor;
            if (style != null && style.getBgColor() != null) {
                bgColor = style.getBgColor();
            } else if (row % 2 == 0) {
                bgColor = new Color(242, 242, 242);
            } else {
                bgColor = config.defaultBgColor;
            }

            if (bgColor != null) {
                g.setColor(bgColor);
                g.fillRect(x, y, w, h);
            }
        }

        /**
         * 绘制单元格内容
         */
        private void drawCellContent(Graphics2D g, String text, int x, int y, int w, int h, CellStyle style) {
            if (text == null || text.isEmpty()) return;
            g.setFont(getFont(style));
            g.setColor(style != null ? style.getTextColor() : Color.BLACK);
            FontMetrics metrics = g.getFontMetrics();
            int hPad = style != null ? style.getHorizontalPadding() : 10;
            int vPad = style != null ? style.getVerticalPadding() : 10;
            int spacing = style != null ? style.getLineSpacing() : 20;
            TextAlignment align = style != null ? style.getAlignment() : TextAlignment.CENTER;
            int maxWidth = w - 2 * hPad;
            String[] lines = text.split("\n");
            int totalTextHeight = lines.length * metrics.getHeight() + (lines.length - 1) * spacing;
            int startY = y + vPad + (h - 2 * vPad - totalTextHeight) / 2 + metrics.getAscent();
            for (String line : lines) {
                if (metrics.stringWidth(line) > maxWidth) {
                    line = truncateText(metrics, line, maxWidth);
                }
                drawSingleLine(g, line, x + hPad, startY, maxWidth, metrics, align);
                startY += metrics.getHeight() + spacing;
            }
        }

        /**
         * 绘制单行
         */
        private void drawSingleLine(Graphics2D g, String line, int startX, int startY, int maxWidth,
                                    FontMetrics metrics, TextAlignment align) {
            int textWidth = metrics.stringWidth(line);
            int finalX = switch (align) {
                case CENTER -> startX + (maxWidth - textWidth) / 2;
                case RIGHT -> startX + maxWidth - textWidth;
                case DISPERSED -> {
                    drawDispersedText(g, line, startX, startY, maxWidth, metrics);
                    yield -1;
                }
                default -> startX;
            };
            if (finalX >= 0) {
                g.drawString(line, finalX, startY);
            }
        }

        /**
         * 绘制分散对齐文本
         */
        private void drawDispersedText(Graphics2D g, String text, int startX, int startY, int maxWidth, FontMetrics metrics) {
            String[] words = text.trim().split("\\s+");
            if (words.length < 2) {
                g.drawString(text, startX, startY);
                return;
            }
            int wordsWidth = Arrays.stream(words).mapToInt(metrics::stringWidth).sum();
            int totalSpace = maxWidth - wordsWidth;
            if (totalSpace <= 0) {
                g.drawString(text, startX, startY);
                return;
            }
            int gapCount = words.length - 1;
            int baseGap = totalSpace / gapCount;
            int extraGaps = totalSpace % gapCount;
            int currentX = startX;
            for (int i = 0; i < words.length; i++) {
                if (!words[i].isEmpty()) {
                    g.drawString(words[i], currentX, startY);
                    currentX += metrics.stringWidth(words[i]);
                }
                if (i < words.length - 1) {
                    currentX += baseGap + (i < extraGaps ? 1 : 0);
                }
            }
        }

        /**
         * 绘制单元格边框
         */
        private void drawCellBorder(Graphics2D g, int x, int y, int w, int h) {
            g.setColor(config.borderColor);
            g.drawRect(x, y, w, h);
        }

        /**
         * 计算跨列宽度
         */
        private int calculateSpanWidth(int startCol, int colSpan) {
            int w = 0;
            for (int i = 0; i < colSpan && (startCol + i) < cols; i++) w += colWidths[startCol + i];
            return w;
        }

        /**
         * 计算跨行高度
         */
        private int calculateSpanHeight(int startRow, int rowSpan) {
            int h = 0;
            for (int i = 0; i < rowSpan && (startRow + i) < rows; i++) h += rowHeights[startRow + i];
            return h;
        }

        /**
         * 获取字体
         */
        private Font getFont(CellStyle style) {
            return (style != null && style.getFont() != null) ? style.getFont() : config.defaultFont;
        }

        /**
         * 获取单元格合并
         */
        private CellMerge getCellMerge(int r, int c) {
            return config.mergeMap.getOrDefault(new Point(c, r), new CellMerge());
        }

        /**
         * 获取单元格样式
         */
        private CellStyle getCellStyle(int r, int c) {
            return config.styleMap.get(new Point(c, r));
        }

        /**
         * 截断文本
         */
        private String truncateText(FontMetrics metrics, String text, int maxWidth) {
            int ellipsisWidth = metrics.stringWidth("...");
            int lo = 0;
            int hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (metrics.stringWidth(text.substring(0, mid)) + ellipsisWidth <= maxWidth) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return text.substring(0, lo) + "...";
        }
    }
}