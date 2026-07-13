package pn.torn.goldeneye.torn.service.activity;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.torn.model.activity.ActivityHeatmapVO;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * 活跃度热力图 PNG 图片渲染器
 *
 * @author Bai
 * @version 1.2.9
 * @since 2026.07.07
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HeatmapImageRenderer {
    // ============ 布局常量 ============
    private static final int CELL_SIZE = 36;
    private static final int HEADER_HEIGHT = 40;
    private static final int ROW_LABEL_WIDTH = 48;
    private static final int LEGEND_HEIGHT = 50;
    private static final int PADDING = 16;

    // 总尺寸
    private static final int IMAGE_WIDTH = PADDING + ROW_LABEL_WIDTH + 24 * CELL_SIZE + PADDING;
    private static final int IMAGE_HEIGHT = HEADER_HEIGHT + 7 * CELL_SIZE + LEGEND_HEIGHT + PADDING;

    // 字体
    private static final String IMAGE_FONT = "Microsoft YaHei";
    private static final Font HEADER_FONT = new Font(IMAGE_FONT, Font.BOLD, 13);
    private static final Font LABEL_FONT = new Font(IMAGE_FONT, Font.PLAIN, 11);
    private static final Font CELL_FONT = new Font(IMAGE_FONT, Font.BOLD, 10);
    private static final Font TITLE_FONT = new Font(IMAGE_FONT, Font.BOLD, 16);

    private static final Color BG_COLOR = new Color(30, 30, 30);
    private static final Color GRID_COLOR = new Color(60, 60, 60);
    private static final Color TEXT_COLOR = new Color(220, 220, 220);
    private static final Color EMPTY_COLOR = new Color(45, 45, 45);

    // 帮派模式：8级颜色（按在线人数）
    private static final Color[] FACTION_COLORS = {
            new Color(45, 45, 45),      // 0
            new Color(144, 238, 144),   // 1-5   浅绿
            new Color(76, 175, 80),     // 6-10  绿色
            new Color(255, 183, 77),    // 11-18 浅橙
            new Color(255, 152, 0),     // 19-30 橙色
            new Color(255, 87, 34),     // 31-50 橙红
            new Color(244, 67, 54),     // 51-75 红色
            new Color(183, 28, 28)      // 76+   深红
    };
    private static final int[] FACTION_THRESHOLDS = {0, 1, 6, 11, 19, 31, 51, 76, Integer.MAX_VALUE};

    // 个人模式：8级颜色（按活跃比例%）
    private static final Color[] PERSONAL_COLORS = {
            new Color(45, 45, 45),      // 0%
            new Color(144, 238, 144),   // 1-15%
            new Color(76, 175, 80),     // 16-30%
            new Color(255, 183, 77),    // 31-45%
            new Color(255, 152, 0),     // 46-60%
            new Color(255, 87, 34),     // 61-75%
            new Color(244, 67, 54),     // 76-90%
            new Color(183, 28, 28)      // 91-100%
    };
    private static final int[] PERSONAL_THRESHOLDS = {0, 1, 16, 31, 46, 61, 76, 91, 101};

    private static final String[] DAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] LEGEND_LABELS_FACTION = {"0", "1-5", "6-10", "11-18", "19-30", "31-50", "51-75", "76+"};
    private static final String[] LEGEND_LABELS_PERSONAL = {"0%", "1-15%", "16-30%", "31-45%", "46-60%", "61-75%", "76-90%", "91-100%"};

    // ============ 对比模式颜色 ============
    // 紫色渐变：正值（我方优势）
    private static final Color[] COMPARISON_POSITIVE_COLORS = {
            new Color(232, 213, 245),   // 1-3    极浅紫
            new Color(187, 143, 206),   // 4-8    中紫
            new Color(155, 89, 182),    // 9-15   紫色
            new Color(108, 52, 131)     // 16+    深紫
    };
    private static final int[] COMPARISON_POSITIVE_THRESHOLDS = {1, 4, 9, 16, Integer.MAX_VALUE};

    // 蓝色渐变：负值（对方优势）
    private static final Color[] COMPARISON_NEGATIVE_COLORS = {
            new Color(213, 232, 245),   // 1-3    极浅蓝
            new Color(133, 193, 233),   // 4-8    中蓝
            new Color(52, 152, 219),    // 9-15   蓝色
            new Color(33, 97, 140)      // 16+    深蓝
    };
    private static final int[] COMPARISON_NEGATIVE_THRESHOLDS = {1, 4, 9, 16, Integer.MAX_VALUE};

    // 对比模式中性色（差值为0）
    private static final Color COMPARISON_NEUTRAL_COLOR = new Color(240, 240, 240);

    // 对比模式图例标签
    private static final String[] COMPARISON_LEGEND_LABELS = {"16+", "9-15", "4-8", "1-3", "0", "1-3", "4-8", "9-15", "16+"};

    /**
     * 渲染热力图 → base64 PNG
     */
    public static String renderAsBase64(ActivityHeatmapVO vo) {
        BufferedImage image = render(vo);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bos);
        } catch (IOException e) {
            throw new BizException("热力图渲染失败", e);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    /**
     * 渲染热力图 → BufferedImage
     */
    public static BufferedImage render(ActivityHeatmapVO vo) {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 背景
        g.setColor(BG_COLOR);
        g.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        if (vo.isDataSufficient()) {
            drawTitle(g, vo.getTitle());
            drawHeader(g);
            drawGrid(g, vo);
            drawRowLabels(g);
            drawLegend(g, !vo.isFactionMode());
        } else {
            drawEmptyMessage(g, vo.getInsufficientMessage());
        }

        g.dispose();
        return image;
    }

    private static void drawTitle(Graphics2D g, String title) {
        g.setFont(TITLE_FONT);
        g.setColor(TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        int x = (IMAGE_WIDTH - fm.stringWidth(title)) / 2;
        g.drawString(title, x, 24);
    }

    private static void drawHeader(Graphics2D g) {
        g.setFont(HEADER_FONT);
        FontMetrics fm = g.getFontMetrics();
        for (int h = 0; h < 24; h++) {
            String label = String.valueOf(h);
            int x = PADDING + ROW_LABEL_WIDTH + h * CELL_SIZE + (CELL_SIZE - fm.stringWidth(label)) / 2;
            int y = HEADER_HEIGHT - 8;
            g.setColor(h % 6 == 0 ? new Color(255, 255, 255) : new Color(160, 160, 160));
            g.drawString(label, x, y);
        }
    }

    private static void drawGrid(Graphics2D g, ActivityHeatmapVO vo) {
        double[][] data = vo.getHeatmap();
        Color[] colors = vo.isFactionMode() ? FACTION_COLORS : PERSONAL_COLORS;
        int[] thresholds = vo.isFactionMode() ? FACTION_THRESHOLDS : PERSONAL_THRESHOLDS;

        for (int dow = 0; dow < 7; dow++) {
            for (int h = 0; h < 24; h++) {
                int x = PADDING + ROW_LABEL_WIDTH + h * CELL_SIZE;
                int y = HEADER_HEIGHT + dow * CELL_SIZE;
                double val = data[dow][h];
                Color cellColor = getCellColor(val, colors, thresholds);
                drawCell(g, x, y, val, cellColor, vo.isFactionMode());
            }
        }
    }

    /**
     * 绘制单个格子（背景色 + 数值文字）
     *
     * @param g             图形上下文
     * @param x             格子左上角x坐标
     * @param y             格子左上角y坐标
     * @param val           格子数值
     * @param cellColor     格子背景色
     * @param isFactionMode 是否为帮派模式
     */
    private static void drawCell(Graphics2D g, int x, int y, double val, Color cellColor, boolean isFactionMode) {
        g.setColor(cellColor);
        g.fillRect(x + 1, y + 1, CELL_SIZE - 2, CELL_SIZE - 2);
        if (val > 0) {
            drawCellText(g, x, y, formatCellValue(val, isFactionMode), cellColor);
        }
    }

    /**
     * 在格子中心绘制文字
     *
     * @param g         图形上下文
     * @param x         格子左上角x坐标
     * @param y         格子左上角y坐标
     * @param text      待绘制文字
     * @param cellColor 格子背景色（用于决定文字颜色）
     */
    private static void drawCellText(Graphics2D g, int x, int y, String text, Color cellColor) {
        g.setFont(CELL_FONT);
        g.setColor(isDarkColor(cellColor) ? Color.WHITE : Color.BLACK);
        FontMetrics fm = g.getFontMetrics();
        int tx = x + (CELL_SIZE - fm.stringWidth(text)) / 2;
        int ty = y + (CELL_SIZE + fm.getAscent()) / 2 - 2;
        g.drawString(text, tx, ty);
    }

    /**
     * 格式化格子数值为显示文字
     *
     * @param val           格子数值
     * @param isFactionMode 是否为帮派模式
     * @return 帮派模式返回整数，个人模式返回百分比
     */
    private static String formatCellValue(double val, boolean isFactionMode) {
        return isFactionMode
                ? String.valueOf((int) Math.round(val))
                : (int) (val * 100) + "%";
    }

    private static void drawRowLabels(Graphics2D g) {
        g.setFont(LABEL_FONT);
        for (int dow = 0; dow < 7; dow++) {
            String label = DAY_LABELS[dow];
            FontMetrics fm = g.getFontMetrics();
            int x = PADDING + (ROW_LABEL_WIDTH - fm.stringWidth(label)) / 2;
            int y = HEADER_HEIGHT + dow * CELL_SIZE + (CELL_SIZE + fm.getAscent()) / 2 - 2;
            g.setColor(TEXT_COLOR);
            g.drawString(label, x, y);
        }
    }

    private static void drawLegend(Graphics2D g, boolean isPersonal) {
        String[] labels = isPersonal ? LEGEND_LABELS_PERSONAL : LEGEND_LABELS_FACTION;
        Color[] colors = isPersonal ? PERSONAL_COLORS : FACTION_COLORS;

        int legendY = HEADER_HEIGHT + 7 * CELL_SIZE + 12;
        int startX = PADDING + ROW_LABEL_WIDTH;
        int legendCellSize = 16;
        int labelWidth = 48;

        g.setFont(new Font(IMAGE_FONT, Font.PLAIN, 10));

        for (int i = 0; i < colors.length; i++) {
            int x = startX + i * (legendCellSize + labelWidth);
            g.setColor(colors[i]);
            g.fillRect(x, legendY, legendCellSize, legendCellSize);
            g.setColor(TEXT_COLOR);
            g.drawString(labels[i], x + legendCellSize + 4, legendY + 13);
        }
    }

    private static void drawEmptyMessage(Graphics2D g, String message) {
        g.setFont(TITLE_FONT);
        g.setColor(new Color(255, 152, 0));
        FontMetrics fm = g.getFontMetrics();
        int x = (IMAGE_WIDTH - fm.stringWidth(message)) / 2;
        int y = IMAGE_HEIGHT / 2;
        g.drawString(message, x, y);
    }

    // ============ 工具方法 ============

    private static Color getCellColor(double val, Color[] colors, int[] thresholds) {
        if (val <= 0) return EMPTY_COLOR;
        for (int i = 0; i < thresholds.length - 1; i++) {
            if (val >= thresholds[i] && val < thresholds[i + 1]) {
                return colors[i];
            }
        }
        return colors[colors.length - 1];
    }

    private static boolean isDarkColor(Color c) {
        return (c.getRed() * 0.299 + c.getGreen() * 0.587 + c.getBlue() * 0.114) < 128;
    }

    // ============ 对比模式渲染 ============

    /**
     * 渲染帮派对比热力图 → base64 PNG
     */
    public static String renderComparisonAsBase64(ActivityHeatmapVO vo) {
        BufferedImage image = renderComparison(vo);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bos);
        } catch (IOException e) {
            throw new BizException("对比热力图渲染失败", e);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    /**
     * 渲染帮派对比热力图 → BufferedImage
     */
    public static BufferedImage renderComparison(ActivityHeatmapVO vo) {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 背景
        g.setColor(BG_COLOR);
        g.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        if (vo.isDataSufficient()) {
            drawComparisonTitle(g, vo);
            drawHeader(g);
            drawComparisonGrid(g, vo);
            drawRowLabels(g);
            drawComparisonLegend(g);
        } else {
            drawEmptyMessage(g, vo.getInsufficientMessage());
        }

        g.dispose();
        return image;
    }

    /**
     * 对比模式标题（含副标题）
     */
    private static void drawComparisonTitle(Graphics2D g, ActivityHeatmapVO vo) {
        g.setFont(TITLE_FONT);
        g.setColor(TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        String title = "帮派活跃度对比";
        int x = (IMAGE_WIDTH - fm.stringWidth(title)) / 2;
        g.drawString(title, x, 16);

        // 副标题：我方(紫) vs 对方(蓝)
        g.setFont(LABEL_FONT);
        String subtitle = vo.getFaction1Name() + "(紫) vs " + vo.getFaction2Name() + "(蓝)";
        fm = g.getFontMetrics();
        x = (IMAGE_WIDTH - fm.stringWidth(subtitle)) / 2;
        g.setColor(new Color(200, 200, 200));
        g.drawString(subtitle, x, 30);
    }

    /**
     * 对比模式格子渲染
     */
    private static void drawComparisonGrid(Graphics2D g, ActivityHeatmapVO vo) {
        double[][] data = vo.getHeatmap();

        drawGridLines(g);

        for (int dow = 0; dow < 7; dow++) {
            for (int h = 0; h < 24; h++) {
                int x = PADDING + ROW_LABEL_WIDTH + h * CELL_SIZE;
                int y = HEADER_HEIGHT + dow * CELL_SIZE;
                double val = data[dow][h];
                Color cellColor = getComparisonCellColor(val);
                drawComparisonCell(g, x, y, val, cellColor);
            }
        }
    }

    /**
     * 绘制对比模式网格线
     *
     * @param g 图形上下文
     */
    private static void drawGridLines(Graphics2D g) {
        g.setColor(GRID_COLOR);
        for (int dow = 0; dow <= 7; dow++) {
            int y = HEADER_HEIGHT + dow * CELL_SIZE;
            g.drawLine(PADDING + ROW_LABEL_WIDTH, y,
                    PADDING + ROW_LABEL_WIDTH + 24 * CELL_SIZE, y);
        }
        for (int h = 0; h <= 24; h++) {
            int x = PADDING + ROW_LABEL_WIDTH + h * CELL_SIZE;
            g.drawLine(x, HEADER_HEIGHT,
                    x, HEADER_HEIGHT + 7 * CELL_SIZE);
        }
    }

    /**
     * 绘制对比模式单个格子（背景色 + 数值文字）
     *
     * @param g         图形上下文
     * @param x         格子左上角x坐标
     * @param y         格子左上角y坐标
     * @param val       格子差值（正=我方优势，负=对方优势，0=平局）
     * @param cellColor 格子背景色
     */
    private static void drawComparisonCell(Graphics2D g, int x, int y, double val, Color cellColor) {
        g.setColor(cellColor);
        g.fillRect(x + 1, y + 1, CELL_SIZE - 2, CELL_SIZE - 2);
        if (val != 0) {
            drawCellText(g, x, y, formatComparisonValue(val), cellColor);
        }
    }

    /**
     * 格式化对比模式格子数值为显示文字
     *
     * @param val 格子差值
     * @return 正值前加"+"号，负值直接显示
     */
    private static String formatComparisonValue(double val) {
        return val > 0
                ? "+" + (int) Math.round(val)
                : String.valueOf((int) Math.round(val));
    }

    /**
     * 对比模式颜色映射
     */
    private static Color getComparisonCellColor(double val) {
        if (val == 0) {
            return COMPARISON_NEUTRAL_COLOR;
        }

        if (val > 0) {
            for (int i = 0; i < COMPARISON_POSITIVE_THRESHOLDS.length - 1; i++) {
                if (val >= COMPARISON_POSITIVE_THRESHOLDS[i]
                        && val < COMPARISON_POSITIVE_THRESHOLDS[i + 1]) {
                    return COMPARISON_POSITIVE_COLORS[i];
                }
            }
            return COMPARISON_POSITIVE_COLORS[COMPARISON_POSITIVE_COLORS.length - 1];
        } else {
            double absVal = Math.abs(val);
            for (int i = 0; i < COMPARISON_NEGATIVE_THRESHOLDS.length - 1; i++) {
                if (absVal >= COMPARISON_NEGATIVE_THRESHOLDS[i]
                        && absVal < COMPARISON_NEGATIVE_THRESHOLDS[i + 1]) {
                    return COMPARISON_NEGATIVE_COLORS[i];
                }
            }
            return COMPARISON_NEGATIVE_COLORS[COMPARISON_NEGATIVE_COLORS.length - 1];
        }
    }

    /**
     * 对比模式图例（蓝→紫渐变）
     */
    private static void drawComparisonLegend(Graphics2D g) {
        Color[] legendColors = {
                COMPARISON_NEGATIVE_COLORS[3],  // 深蓝 16+
                COMPARISON_NEGATIVE_COLORS[2],  // 蓝色 9-15
                COMPARISON_NEGATIVE_COLORS[1],  // 中蓝 4-8
                COMPARISON_NEGATIVE_COLORS[0],  // 浅蓝 1-3
                COMPARISON_NEUTRAL_COLOR,       // 中性 0
                COMPARISON_POSITIVE_COLORS[0],  // 浅紫 1-3
                COMPARISON_POSITIVE_COLORS[1],  // 中紫 4-8
                COMPARISON_POSITIVE_COLORS[2],  // 紫色 9-15
                COMPARISON_POSITIVE_COLORS[3]   // 深紫 16+
        };

        int legendY = HEADER_HEIGHT + 7 * CELL_SIZE + 12;
        int legendCellSize = 14;
        int labelWidth = 36;
        int totalLegendWidth = legendColors.length * legendCellSize
                + (legendColors.length - 1) * (labelWidth - legendCellSize + 4);
        int startX = PADDING + ROW_LABEL_WIDTH
                + (24 * CELL_SIZE - totalLegendWidth) / 2;

        g.setFont(new Font(IMAGE_FONT, Font.PLAIN, 9));

        for (int i = 0; i < legendColors.length; i++) {
            int x = startX + i * (legendCellSize + labelWidth - legendCellSize + 4);
            g.setColor(legendColors[i]);
            g.fillRect(x, legendY, legendCellSize, legendCellSize);
            g.setColor(TEXT_COLOR);
            g.drawString(COMPARISON_LEGEND_LABELS[i], x + legendCellSize + 3, legendY + 12);
        }
    }
}
