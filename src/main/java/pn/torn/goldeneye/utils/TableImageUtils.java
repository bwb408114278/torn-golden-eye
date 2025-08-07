package pn.torn.goldeneye.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.base.exception.BizException;

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
    // 文本对齐方式枚举
    public enum TextAlignment {
        LEFT,        // 左对齐
        DISPERSED    // 分散对齐
    }

    // 表格渲染配置类
    public static class TableConfig {
        private final Set<Integer> mergedRows = new HashSet<>(); // 需要整行合并的行索引
        private final Map<Integer, TextAlignment> rowAlignments = new HashMap<>(); // 行对齐方式映射
        private int cellPadding = 10;
        private int cellHeight = 40;
        private Font font = new Font("微软雅黑", Font.BOLD, 14);

        public TableConfig addMergedRow(int rowIndex) {
            mergedRows.add(rowIndex);
            return this;
        }

        public TableConfig setRowAlignment(int rowIndex, TextAlignment alignment) {
            rowAlignments.put(rowIndex, alignment);
            return this;
        }

        public TableConfig setCellPadding(int padding) {
            this.cellPadding = padding;
            return this;
        }

        public TableConfig setCellHeight(int height) {
            this.cellHeight = height;
            return this;
        }

        public TableConfig setFont(Font font) {
            this.font = font;
            return this;
        }
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
        final int cols = tableData.get(0).size();
        final int rows = tableData.size();

        // 获取配置参数
        int cellPadding = config.cellPadding;
        int cellHeight = config.cellHeight;
        Font font = config.font;
        Set<Integer> mergedRows = config.mergedRows;
        Map<Integer, TextAlignment> rowAlignments = config.rowAlignments;

        // 创建临时Graphics获取字体尺寸
        BufferedImage tempImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D tempG = tempImage.createGraphics();
        FontMetrics metrics = tempG.getFontMetrics(font);
        tempG.dispose();

        // 1. 计算基本列宽（忽略合并行）
        int[] colWidths = new int[cols];
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                if (mergedRows.contains(r)) continue; // 跳过合并行

                List<String> row = tableData.get(r);
                if (c >= row.size()) continue;

                String cellText = Objects.toString(row.get(c), "");
                int width = metrics.stringWidth(cellText) + 2 * cellPadding;
                if (width > colWidths[c]) colWidths[c] = width;
            }
        }

        // 2. 处理合并行对列宽的影响
        int baseTotalWidth = Arrays.stream(colWidths).sum();
        int maxFullRowWidth = 0;

        for (int r = 0; r < rows; r++) {
            if (mergedRows.contains(r)) {
                List<String> row = tableData.get(r);
                String text = row.isEmpty() ? "" : row.get(0);
                int cellWidth = metrics.stringWidth(text) + 2 * cellPadding;
                if (cellWidth > maxFullRowWidth) maxFullRowWidth = cellWidth;
            }
        }

        // 调整表格总宽度（如果需要）
        int totalWidth = Math.max(baseTotalWidth, maxFullRowWidth);
        if (maxFullRowWidth > baseTotalWidth) {
            // 按比例增加列宽
            int extra = maxFullRowWidth - baseTotalWidth;
            for (int i = 0; i < cols; i++) {
                colWidths[i] += (colWidths[i] * extra) / baseTotalWidth;
            }
            totalWidth = maxFullRowWidth; // 更新总宽度
        }

        // 3. 创建正式图片
        int totalHeight = rows * cellHeight;
        BufferedImage image = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = image.createGraphics();
        try {
            configureGraphics(g, font, totalWidth, totalHeight);
            drawTable(g, tableData, colWidths, cellPadding, cellHeight, metrics, totalWidth, mergedRows, rowAlignments);
        } finally {
            g.dispose();
        }
        return image;
    }

    // 配置图形参数
    private static void configureGraphics(Graphics2D g, Font font, int width, int height) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
    }

    // 绘制表格
    private static void drawTable(
            Graphics2D g,
            List<List<String>> tableData,
            int[] colWidths,
            int padding,
            int rowHeight,
            FontMetrics metrics,
            int totalWidth,
            Set<Integer> mergedRows,
            Map<Integer, TextAlignment> rowAlignments
    ) {
        for (int r = 0; r < tableData.size(); r++) {
            List<String> row = tableData.get(r);
            int y = r * rowHeight;

            // 行背景色
            if (r % 2 == 0) {
                g.setColor(new Color(240, 240, 240));
                g.fillRect(0, y, totalWidth, rowHeight);
            }

            // 判断是否是合并行
            if (mergedRows.contains(r)) {
                drawMergedCell(g, row, padding, rowHeight, metrics, y, totalWidth);
            } else {
                // 正常单元格绘制
                int x = 0;
                for (int c = 0; c < colWidths.length; c++) {
                    g.setColor(Color.LIGHT_GRAY);
                    g.drawRect(x, y, colWidths[c], rowHeight);

                    // 获取单元格文本（确保不为null）
                    String text = (c < row.size() && row.get(c) != null) ? row.get(c) : "";
                    TextAlignment alignment = rowAlignments.getOrDefault(r, TextAlignment.LEFT);

                    drawCellContent(g, text, x, y, colWidths[c], rowHeight, padding, metrics, alignment);
                    x += colWidths[c];
                }
            }
        }
    }

    // 绘制合并单元格
    private static void drawMergedCell(
            Graphics2D g,
            List<String> row,
            int padding,
            int rowHeight,
            FontMetrics metrics,
            int y,
            int totalWidth
    ) {
        g.setColor(Color.LIGHT_GRAY);
        g.drawRect(0, y, totalWidth, rowHeight);

        // 获取单元格文本（取第一列内容）
        String text = row.isEmpty() ? "" : Objects.toString(row.get(0), "");
        drawCellContent(g, text, 0, y, totalWidth, rowHeight, padding, metrics, TextAlignment.LEFT);
    }

    // 绘制单元格内容（支持不同对齐方式）
    private static void drawCellContent(
            Graphics2D g,
            String text,
            int cellX,
            int cellY,
            int cellWidth,
            int cellHeight,
            int padding,
            FontMetrics metrics,
            TextAlignment alignment
    ) {
        g.setColor(Color.BLACK);

        // 计算文本基线位置
        int baselineY = cellY + (cellHeight + metrics.getAscent() - metrics.getDescent()) / 2;
        int maxTextWidth = cellWidth - 2 * padding;

        // 文本截断处理
        if (metrics.stringWidth(text) > maxTextWidth) {
            text = truncateText(metrics, text, maxTextWidth);
        }

        // 根据对齐方式绘制
        switch (alignment) {
            case DISPERSED:
                drawDispersedText(g, text, cellX + padding, baselineY, maxTextWidth, metrics);
                break;
            case LEFT:
            default:
                g.drawString(text, cellX + padding, baselineY);
        }
    }

    // 绘制分散对齐文本
    private static void drawDispersedText(
            Graphics2D g,
            String text,
            int startX,
            int baselineY,
            int maxWidth,
            FontMetrics metrics
    ) {
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

    // 文本截断处理
    private static String truncateText(FontMetrics metrics, String text, int maxWidth) {
        final String ellipsis = "...";
        int ellipsisWidth = metrics.stringWidth(ellipsis);

        int len = text.length();
        while (len > 0 && metrics.stringWidth(text.substring(0, len)) + ellipsisWidth > maxWidth) {
            len--;
        }
        return (len > 0 ? text.substring(0, len) : "") + ellipsis;
    }

    // 转换为Base64
    private static String convertToBase64(BufferedImage image) {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", stream);
            return Base64.getEncoder().encodeToString(stream.toByteArray());
        } catch (Exception e) {
            throw new BizException("图片转Base64异常", e);
        }
    }
}