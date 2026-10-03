package pn.torn.goldeneye.torn.service.activity;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.torn.model.activity.ActivityComparisonHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.BaseActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.FactionActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.grid.ActivityGridLayout;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 活跃度热力图 PNG 图片渲染器
 * <p>
 * 负责个人活跃度热力图、帮派活跃度热力图和帮派活跃度对比图的 PNG 渲染。
 * 普通图与对比图共用统一布局；副标题支持两行绘制（第一行指标/覆盖率说明，
 * 第二行数据不完整与 legacy 提示），存在第二行时布局高度相应增加，禁止文本重叠或截断。
 * 颜色、暗化与人数渐变锚点全部来自{@link HeatmapColorScale}。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.07.21
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HeatmapImageRenderer {

    // ============ 布局常量 ============
    /**
     * 外边距
     */
    private static final int PADDING = 16;
    /**
     * 标题区高度
     */
    private static final int TITLE_HEIGHT = 28;
    /**
     * 副标题单行高度
     */
    private static final int SUBTITLE_HEIGHT = 20;
    /**
     * 时间轴区高度
     */
    private static final int TIME_AXIS_HEIGHT = 24;
    /**
     * 单元格尺寸（正方形）
     */
    private static final int CELL_SIZE = 36;
    /**
     * 行标签宽度
     */
    private static final int ROW_LABEL_WIDTH = 48;
    /**
     * 图例区高度
     */
    private static final int LEGEND_HEIGHT = 44;

    // ============ Y 坐标分区 ============
    /**
     * 标题区顶部 Y
     */
    private static final int TITLE_Y = PADDING;
    /**
     * 副标题区顶部 Y
     */
    private static final int SUBTITLE_Y = TITLE_Y + TITLE_HEIGHT;

    // ============ X 坐标 ============
    /**
     * 网格区左侧 X（行标签宽度固定，与网格列数无关）
     */
    private static final int GRID_X = PADDING + ROW_LABEL_WIDTH;

    // ============ 字体 ============
    private static final String IMAGE_FONT = "Microsoft YaHei";
    /**
     * 标题字体
     */
    private static final Font TITLE_FONT = new Font(IMAGE_FONT, Font.BOLD, 15);
    /**
     * 副标题字体
     */
    private static final Font SUBTITLE_FONT = new Font(IMAGE_FONT, Font.PLAIN, 11);
    /**
     * 时间轴表头字体
     */
    private static final Font HEADER_FONT = new Font(IMAGE_FONT, Font.BOLD, 12);
    /**
     * 行标签字体
     */
    private static final Font LABEL_FONT = new Font(IMAGE_FONT, Font.PLAIN, 11);
    /**
     * 格内文字字体
     */
    private static final Font CELL_FONT = new Font(IMAGE_FONT, Font.BOLD, 10);

    // ============ 其他常量 ============
    /**
     * 数据不完整/legacy 提示文字颜色（橙色）
     */
    private static final Color NOTICE_COLOR = new Color(255, 152, 0);
    /**
     * 个人图图例刻度
     */
    private static final int[] LEGEND_TICKS = {0, 25, 50, 75, 100};
    /**
     * 对比图标题固定文案
     */
    private static final String COMPARISON_TITLE = "帮派活跃度对比";
    /**
     * 无数据符号
     */
    private static final String NO_DATA_SYMBOL = "-";
    /**
     * 百分号
     */
    private static final String PERCENT = "%";

    /**
     * 动态布局：口径网格决定行列数与宽度，副标题行数决定纵向位置
     *
     * @param imageWidth  图片总宽度
     * @param gridWidth   网格区总宽度
     * @param imageHeight 图片总高度
     * @param timeAxisY   时间轴区顶部 Y
     * @param gridY       网格区顶部 Y
     * @param legendY     图例区顶部 Y
     * @param grid        口径网格，提供行列数与行列标签
     */
    private record HeatmapLayout(
            int imageWidth,
            int gridWidth,
            int imageHeight,
            int timeAxisY,
            int gridY,
            int legendY,
            ActivityGridLayout grid) {
    }

    // ==================== 个人图渲染入口 ====================

    /**
     * 渲染个人活跃度热力图为 base64 PNG 字符串
     *
     * @param vo 个人活跃度热力图数据
     * @return base64 编码的 PNG 字符串
     * @throws BizException 渲染或编码失败时抛出
     */
    public static String renderPersonalAsBase64(PersonalActivityHeatmapVO vo) {
        return encodeAsBase64Png(renderPersonal(vo));
    }

    /**
     * 渲染个人活跃度热力图为 BufferedImage
     * <p>
     * 有效格颜色为连续比例色板按 idleRatio 连续暗化后的颜色。
     *
     * @param vo 个人活跃度热力图数据
     * @return 渲染完成的图片
     */
    public static BufferedImage renderPersonal(PersonalActivityHeatmapVO vo) {
        HeatmapLayout layout = layoutFor(vo);
        BufferedImage image = createCanvas(layout);
        Graphics2D g = image.createGraphics();
        try {
            applyRenderingHints(g, image);
            if (vo.isHasData()) {
                drawTitle(g, layout, vo.getTitle());
                drawSubtitleLines(g, layout, vo);
                drawTimeAxis(g, layout);
                drawRowLabels(g, layout);
                drawPersonalGrid(g, vo, layout);
                drawActivityLegend(g, layout);
            } else {
                drawInsufficientMessage(g, layout, ActivityHeatmapService.NO_DATA_MESSAGE);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    // ==================== 帮派图渲染入口 ====================

    /**
     * 渲染帮派活跃度热力图为 base64 PNG 字符串
     *
     * @param vo 帮派活跃度热力图数据
     * @return base64 编码的 PNG 字符串
     * @throws BizException 渲染或编码失败时抛出
     */
    public static String renderFactionAsBase64(FactionActivityHeatmapVO vo) {
        return encodeAsBase64Png(renderFaction(vo));
    }

    /**
     * 渲染帮派活跃度热力图为 BufferedImage
     * <p>
     * 格内数字为平均有效活跃人数，颜色为人数 5 档主色按 idleRatio 连续暗化。
     *
     * @param vo 帮派活跃度热力图数据
     * @return 渲染完成的图片
     */
    public static BufferedImage renderFaction(FactionActivityHeatmapVO vo) {
        HeatmapLayout layout = layoutFor(vo);
        BufferedImage image = createCanvas(layout);
        Graphics2D g = image.createGraphics();
        try {
            applyRenderingHints(g, image);
            if (vo.isHasData()) {
                drawTitle(g, layout, vo.getTitle());
                drawSubtitleLines(g, layout, vo);
                drawTimeAxis(g, layout);
                drawRowLabels(g, layout);
                drawFactionGrid(g, vo, layout);
                drawFactionLegend(g, layout);
            } else {
                drawInsufficientMessage(g, layout, ActivityHeatmapService.NO_DATA_MESSAGE);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    // ==================== 对比图渲染入口 ====================

    /**
     * 渲染帮派活跃度对比热力图为 base64 PNG 字符串
     *
     * @param vo 帮派活跃度对比热力图数据
     * @return base64 编码的 PNG 字符串
     * @throws BizException 渲染或编码失败时抛出
     */
    public static String renderComparisonAsBase64(ActivityComparisonHeatmapVO vo) {
        return encodeAsBase64Png(renderComparison(vo));
    }

    /**
     * 渲染帮派活跃度对比热力图为 BufferedImage
     * <p>
     * 仅在 bothObserved=true 的格子计算 diff 并着色；颜色与 P95 差值算法保持既有实现，
     * Idle 不参与对比和色差。
     *
     * @param vo 帮派活跃度对比热力图数据
     * @return 渲染完成的图片
     */
    public static BufferedImage renderComparison(ActivityComparisonHeatmapVO vo) {
        HeatmapLayout layout = layoutFor(vo);
        BufferedImage image = createCanvas(layout);
        Graphics2D g = image.createGraphics();
        try {
            applyRenderingHints(g, image);
            if (vo.isHasData()) {
                drawTitle(g, layout, COMPARISON_TITLE);
                drawSubtitleLines(g, layout, vo);
                drawTimeAxis(g, layout);
                drawRowLabels(g, layout);
                drawComparisonGrid(g, vo, layout);
                drawComparisonLegend(g, layout);
            } else {
                drawInsufficientMessage(g, layout, ActivityHeatmapService.NO_DATA_MESSAGE);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    // ==================== 画布与编码 ====================

    /**
     * 根据口径网格与副标题行数计算动态布局
     *
     * @param vo 热力图数据
     * @return 动态布局
     */
    private static HeatmapLayout layoutFor(BaseActivityHeatmapVO vo) {
        ActivityGridLayout grid = vo.getGrid();
        int subtitleLineCount = hasNoticeLine(vo) ? 2 : 1;
        int timeAxisY = SUBTITLE_Y + subtitleLineCount * SUBTITLE_HEIGHT;
        int gridY = timeAxisY + TIME_AXIS_HEIGHT;
        int gridWidth = grid.cols() * CELL_SIZE;
        int imageWidth = PADDING + ROW_LABEL_WIDTH + gridWidth + PADDING;
        int legendY = gridY + grid.rows() * CELL_SIZE;
        int imageHeight = legendY + LEGEND_HEIGHT + PADDING;
        return new HeatmapLayout(imageWidth, gridWidth, imageHeight, timeAxisY, gridY, legendY, grid);
    }

    /**
     * 判断副标题第二行（数据不完整/legacy 提示）是否存在
     *
     * @param vo 热力图数据
     * @return true 表示存在第二行提示
     */
    private static boolean hasNoticeLine(BaseActivityHeatmapVO vo) {
        return vo.getNoticeMessage() != null && !vo.getNoticeMessage().isBlank();
    }

    /**
     * 创建指定布局的空画布
     *
     * @param layout 动态布局
     * @return 未填充背景的 BufferedImage
     */
    private static BufferedImage createCanvas(HeatmapLayout layout) {
        return new BufferedImage(layout.imageWidth(), layout.imageHeight(), BufferedImage.TYPE_INT_RGB);
    }

    /**
     * 应用抗锯齿渲染提示并填充背景色
     *
     * @param g     图形上下文
     * @param image 目标图片
     */
    private static void applyRenderingHints(Graphics2D g, BufferedImage image) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(HeatmapColorScale.BG_COLOR);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
    }

    /**
     * 将 BufferedImage 编码为 base64 PNG 字符串
     *
     * @param image 待编码图片
     * @return base64 字符串
     * @throws BizException 编码失败时抛出
     */
    private static String encodeAsBase64Png(BufferedImage image) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bos);
        } catch (IOException e) {
            throw new BizException("热力图渲染失败", e);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    // ==================== 通用绘制 ====================

    /**
     * 绘制标题（水平居中于图片、垂直居中于标题区）
     *
     * @param g      图形上下文
     * @param layout 动态布局
     * @param title  标题文字
     */
    private static void drawTitle(Graphics2D g, HeatmapLayout layout, String title) {
        g.setFont(TITLE_FONT);
        g.setColor(HeatmapColorScale.TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        int x = (layout.imageWidth() - fm.stringWidth(title)) / 2;
        int baselineY = TITLE_Y + (TITLE_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(title, x, baselineY);
    }

    /**
     * 绘制两行副标题：第一行为指标/覆盖率说明，第二行（存在时）为数据不完整/legacy 提示
     *
     * @param g      图形上下文
     * @param layout 动态布局
     * @param vo     热力图数据
     */
    private static void drawSubtitleLines(Graphics2D g, HeatmapLayout layout, BaseActivityHeatmapVO vo) {
        String subtitle = resolveSubtitleLine1(vo);
        if (subtitle != null && !subtitle.isBlank()) {
            drawSubtitleLine(g, layout, subtitle, SUBTITLE_Y, HeatmapColorScale.SUB_TEXT_COLOR);
        }
        if (hasNoticeLine(vo)) {
            drawSubtitleLine(g, layout, vo.getNoticeMessage(), SUBTITLE_Y + SUBTITLE_HEIGHT, NOTICE_COLOR);
        }
    }

    /**
     * 解析副标题第一行文字：优先 VO 副标题，缺失时按个人图旧口径绘制覆盖率
     *
     * @param vo 热力图数据
     * @return 副标题第一行文字
     */
    private static String resolveSubtitleLine1(BaseActivityHeatmapVO vo) {
        if (vo instanceof PersonalActivityHeatmapVO personal) {
            return personal.getSubtitle() != null ? personal.getSubtitle()
                    : "有效采样覆盖率: " + (int) Math.round(vo.getCoverage() * 100) + PERCENT;
        }
        if (vo instanceof FactionActivityHeatmapVO faction) {
            return faction.getSubtitle();
        }
        if (vo instanceof ActivityComparisonHeatmapVO comparison) {
            return comparison.getSubtitle();
        }
        return null;
    }

    /**
     * 在指定副标题行区域内水平垂直居中绘制单行文字
     *
     * @param g      图形上下文
     * @param layout 动态布局
     * @param text   文字
     * @param lineY  该行顶部 Y
     * @param color  文字颜色
     */
    private static void drawSubtitleLine(Graphics2D g, HeatmapLayout layout, String text,
                                         int lineY, Color color) {
        g.setFont(SUBTITLE_FONT);
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        int x = (layout.imageWidth() - fm.stringWidth(text)) / 2;
        int baselineY = lineY + (SUBTITLE_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(text, x, baselineY);
    }

    /**
     * 绘制时间轴：列标签由口径网格提供，每 6 列使用主文字色高亮
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    private static void drawTimeAxis(Graphics2D g, HeatmapLayout layout) {
        g.setFont(HEADER_FONT);
        FontMetrics fm = g.getFontMetrics();
        int baselineY = layout.timeAxisY() + (TIME_AXIS_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
        for (int h = 0; h < layout.grid().cols(); h++) {
            String label = layout.grid().colLabel(h);
            int x = GRID_X + h * CELL_SIZE + (CELL_SIZE - fm.stringWidth(label)) / 2;
            g.setColor(h % 6 == 0 ? HeatmapColorScale.TEXT_COLOR : HeatmapColorScale.SUB_TEXT_COLOR);
            g.drawString(label, x, baselineY);
        }
    }

    /**
     * 绘制行标签，文案由口径网格提供
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    private static void drawRowLabels(Graphics2D g, HeatmapLayout layout) {
        g.setFont(LABEL_FONT);
        g.setColor(HeatmapColorScale.TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        for (int row = 0; row < layout.grid().rows(); row++) {
            String label = layout.grid().rowLabel(row);
            int x = PADDING + (ROW_LABEL_WIDTH - fm.stringWidth(label)) / 2;
            int baselineY = layout.gridY() + row * CELL_SIZE + (CELL_SIZE + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(label, x, baselineY);
        }
    }

    /**
     * 绘制网格线（画在格子最上层，确保边界清晰）
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    private static void drawGridLines(Graphics2D g, HeatmapLayout layout) {
        g.setColor(HeatmapColorScale.GRID_COLOR);
        int rows = layout.grid().rows();
        for (int row = 0; row <= rows; row++) {
            int y = layout.gridY() + row * CELL_SIZE;
            g.drawLine(GRID_X, y, GRID_X + layout.gridWidth(), y);
        }
        for (int col = 0; col <= layout.grid().cols(); col++) {
            int x = GRID_X + col * CELL_SIZE;
            g.drawLine(x, layout.gridY(), x, layout.gridY() + rows * CELL_SIZE);
        }
    }

    /**
     * 绘制单个格子（背景色 + 居中文字）
     *
     * @param g         图形上下文
     * @param x         格子左上角 x 坐标
     * @param y         格子左上角 y 坐标
     * @param text      格内文字（null 或空串表示不绘制文字）
     * @param cellColor 格子背景色
     * @param textColor 文字颜色
     */
    private static void drawCell(Graphics2D g, int x, int y, String text, Color cellColor, Color textColor) {
        g.setColor(cellColor);
        g.fillRect(x, y, CELL_SIZE, CELL_SIZE);
        if (text != null && !text.isEmpty()) {
            g.setFont(CELL_FONT);
            g.setColor(textColor);
            FontMetrics fm = g.getFontMetrics();
            int tx = x + (CELL_SIZE - fm.stringWidth(text)) / 2;
            int ty = y + (CELL_SIZE + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(text, tx, ty);
        }
    }

    /**
     * 绘制无数据格子，显示 "-" 符号
     *
     * @param g 图形上下文
     * @param x 格子左上角 x 坐标
     * @param y 格子左上角 y 坐标
     */
    private static void drawEmptyCell(Graphics2D g, int x, int y) {
        drawCell(g, x, y, NO_DATA_SYMBOL,
                HeatmapColorScale.EMPTY_COLOR, HeatmapColorScale.NO_DATA_SYMBOL_COLOR);
    }

    /**
     * 绘制无数据整图提示（居中橙色文字），防御性保留给 hasData=false 的调用
     *
     * @param g       图形上下文
     * @param layout  动态布局
     * @param message 提示信息
     */
    private static void drawInsufficientMessage(Graphics2D g, HeatmapLayout layout, String message) {
        g.setFont(TITLE_FONT);
        g.setColor(NOTICE_COLOR);
        FontMetrics fm = g.getFontMetrics();
        int x = (layout.imageWidth() - fm.stringWidth(message)) / 2;
        int y = layout.imageHeight() / 2;
        g.drawString(message, x, y);
    }

    // ==================== 个人图格子 ====================

    /**
     * 绘制个人图口径网格
     * <p>
     * 无数据格显示 "-"；有效格颜色为连续比例色板按 idleRatio 连续暗化，
     * 已观测且有效活跃为 0 的格显示 "0%" 使用渐变起点色。
     *
     * @param g      图形上下文
     * @param vo     个人热力图数据
     * @param layout 动态布局
     */
    private static void drawPersonalGrid(Graphics2D g, PersonalActivityHeatmapVO vo, HeatmapLayout layout) {
        double[][] activeRate = vo.getActiveRate();
        double[][] idleRatio = vo.getIdleRatio();
        int[][] observed = vo.getObservedSamples();
        for (int row = 0; row < layout.grid().rows(); row++) {
            for (int col = 0; col < layout.grid().cols(); col++) {
                int x = GRID_X + col * CELL_SIZE;
                int y = layout.gridY() + row * CELL_SIZE;
                if (observed[row][col] == 0) {
                    drawEmptyCell(g, x, y);
                } else {
                    double rate = activeRate[row][col];
                    Color cellColor = HeatmapColorScale.darkenedActivityColor(rate, idleRatio[row][col]);
                    String text = formatPercent(rate);
                    drawCell(g, x, y, text, cellColor, HeatmapColorScale.textColorFor(cellColor));
                }
            }
        }
        drawGridLines(g, layout);
    }

    // ==================== 帮派图格子 ====================

    /**
     * 绘制帮派图口径网格
     * <p>
     * 格内显示平均有效活跃人数（单一数字）；颜色为人数锚点渐变主色按 idleRatio 连续暗化，
     * I 不改变格内数字与渐变位置；已观测且有效活跃为 0 时仍使用首锚点主色（含暗化），
     * 与无数据深灰格区分。
     *
     * @param g      图形上下文
     * @param vo     帮派热力图数据
     * @param layout 动态布局
     */
    private static void drawFactionGrid(Graphics2D g, FactionActivityHeatmapVO vo, HeatmapLayout layout) {
        double[][] averageActiveCount = vo.getAverageOnlineCount();
        double[][] idleRatio = vo.getIdleRatio();
        int[][] observed = vo.getObservedSamples();
        for (int row = 0; row < layout.grid().rows(); row++) {
            for (int col = 0; col < layout.grid().cols(); col++) {
                int x = GRID_X + col * CELL_SIZE;
                int y = layout.gridY() + row * CELL_SIZE;
                if (observed[row][col] == 0) {
                    drawEmptyCell(g, x, y);
                } else {
                    Color cellColor = HeatmapColorScale.factionColor(
                            averageActiveCount[row][col], idleRatio[row][col]);
                    String text = String.valueOf((int) Math.round(averageActiveCount[row][col]));
                    drawCell(g, x, y, text, cellColor, HeatmapColorScale.textColorFor(cellColor));
                }
            }
        }
        drawGridLines(g, layout);
    }

    // ==================== 对比图格子 ====================

    /**
     * 绘制对比图口径网格
     * <p>
     * 仅在 bothObserved=true 的格子计算 diff 并着色，无数据格不显示文字。
     * scale 为 0 时所有有效格统一使用 COMPARISON_NEUTRAL_COLOR。
     *
     * @param g      图形上下文
     * @param vo     对比热力图数据
     * @param layout 动态布局
     */
    private static void drawComparisonGrid(Graphics2D g, ActivityComparisonHeatmapVO vo, HeatmapLayout layout) {
        double[][] f1 = vo.getFaction1AverageOnline();
        double[][] f2 = vo.getFaction2AverageOnline();
        boolean[][] bothObserved = vo.getBothObserved();

        double scale = calculateComparisonScale(f1, f2, bothObserved);
        boolean useNeutral = scale == 0;

        for (int row = 0; row < layout.grid().rows(); row++) {
            for (int col = 0; col < layout.grid().cols(); col++) {
                int x = GRID_X + col * CELL_SIZE;
                int y = layout.gridY() + row * CELL_SIZE;
                if (!bothObserved[row][col]) {
                    // 无数据格：EMPTY_COLOR，不显示文字
                    drawCell(g, x, y, null, HeatmapColorScale.EMPTY_COLOR, HeatmapColorScale.NO_DATA_SYMBOL_COLOR);
                    continue;
                }
                double diff = f1[row][col] - f2[row][col];
                Color cellColor;
                if (useNeutral) {
                    cellColor = HeatmapColorScale.COMPARISON_NEUTRAL_COLOR;
                } else {
                    double normalized = HeatmapColorScale.normalizeComparisonDiff(diff, scale);
                    cellColor = HeatmapColorScale.comparisonColor(normalized);
                }
                String text = formatComparisonCell(f1[row][col], f2[row][col]);
                drawCell(g, x, y, text, cellColor, HeatmapColorScale.textColorFor(cellColor));
            }
        }
        drawGridLines(g, layout);
    }

    /**
     * 计算对比图 P95 scale
     * <p>
     * 收集所有 bothObserved=true 格子的 abs(diff)，排序后取第 95 百分位。
     * 若共同有效格子数 <= 1，scale = abs(那个值)（空列表返回 0）。
     *
     * @param f1           帮派A 平均有效活跃人数矩阵
     * @param f2           帮派B 平均有效活跃人数矩阵
     * @param bothObserved 共同有效采样标记矩阵
     * @return P95 scale 值
     */
    private static double calculateComparisonScale(double[][] f1, double[][] f2, boolean[][] bothObserved) {
        List<Double> absDiffs = new ArrayList<>();
        for (int row = 0; row < bothObserved.length; row++) {
            for (int col = 0; col < bothObserved[row].length; col++) {
                if (bothObserved[row][col]) {
                    absDiffs.add(Math.abs(f1[row][col] - f2[row][col]));
                }
            }
        }
        int n = absDiffs.size();
        if (n == 0) {
            return 0;
        }
        if (n == 1) {
            return absDiffs.getFirst();
        }
        Collections.sort(absDiffs);
        return p95(absDiffs);
    }

    /**
     * 计算排序后列表的 P95（线性插值法）
     *
     * @param sorted 已排序的数值列表
     * @return P95 百分位值
     */
    private static double p95(List<Double> sorted) {
        int n = sorted.size();
        if (n == 1) {
            return sorted.getFirst();
        }
        double rank = 0.95 * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double fraction = rank - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }

    /**
     * 格式化对比格子文字："A人数/B人数"
     *
     * @param a 帮派A 人数
     * @param b 帮派B 人数
     * @return 格式化文字，如 "23/18"
     */
    private static String formatComparisonCell(double a, double b) {
        return (int) Math.round(a) + "/" + (int) Math.round(b);
    }

    // ==================== 图例 ====================

    /**
     * 图例标签绘制上下文：渐变条位置与标签布局度量，个人/帮派/对比图例共用
     *
     * @param barX     渐变条左上角 x
     * @param barWidth 渐变条宽度
     * @param labelY   标签基线 y
     * @param fm       标签字体度量
     */
    private record LegendLabelContext(
            int barX,
            int barWidth,
            int labelY,
            FontMetrics fm) {
    }

    /**
     * 绘制图例渐变条并准备标签上下文（个人/帮派/对比图例共用前导）
     *
     * @param g             图形上下文
     * @param layout        动态布局
     * @param colorFunction 渐变条取色函数，输入 [0,1] 归一化位置
     * @return 标签绘制上下文
     */
    private static LegendLabelContext drawLegendBar(Graphics2D g, HeatmapLayout layout,
                                                    java.util.function.DoubleFunction<Color> colorFunction) {
        int barX = GRID_X;
        int barWidth = layout.gridWidth();
        int barY = layout.legendY() + 6;
        drawGradientBar(g, barX, barY, barWidth, colorFunction);

        g.setFont(LABEL_FONT);
        g.setColor(HeatmapColorScale.SUB_TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        return new LegendLabelContext(barX, barWidth, barY + 12 + fm.getAscent() + 2, fm);
    }

    /**
     * 绘制个人图连续渐变图例
     * <p>
     * 水平渐变条，每个像素调用 {@link HeatmapColorScale#activityColor} 生成；
     * 刻度标注 0%、25%、50%、75%、100%。
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    private static void drawActivityLegend(Graphics2D g, HeatmapLayout layout) {
        LegendLabelContext ctx = drawLegendBar(g, layout, HeatmapColorScale::activityColor);
        FontMetrics fm = ctx.fm();
        for (int tick : LEGEND_TICKS) {
            int tickX = ctx.barX() + (int) Math.round(tick / 100.0 * ctx.barWidth());
            String label = tick + PERCENT;
            int labelWidth = fm.stringWidth(label);
            int lx = switch (tick) {
                case 0 -> tickX;
                case 100 -> tickX - labelWidth;
                default -> tickX - labelWidth / 2;
            };
            g.drawString(label, lx, ctx.labelY());
        }
    }

    /**
     * 绘制帮派图连续渐变图例
     * <p>
     * 水平渐变条，每个像素调用 {@link HeatmapColorScale#factionLegendColor} 生成；
     * 刻度按锚点下标等距标注 0/25/50/75/100+，首尾标签对齐条两端。
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    private static void drawFactionLegend(Graphics2D g, HeatmapLayout layout) {
        LegendLabelContext ctx = drawLegendBar(g, layout, HeatmapColorScale::factionLegendColor);
        FontMetrics fm = ctx.fm();
        String[] labels = HeatmapColorScale.FACTION_ANCHOR_LABELS;
        for (int i = 0; i < labels.length; i++) {
            int tickX = ctx.barX() + (int) Math.round(i / (labels.length - 1.0) * ctx.barWidth());
            String label = labels[i];
            int labelWidth = fm.stringWidth(label);
            int lx;
            if (i == 0) {
                lx = tickX;
            } else if (i == labels.length - 1) {
                lx = tickX - labelWidth;
            } else {
                lx = tickX - labelWidth / 2;
            }
            g.drawString(label, lx, ctx.labelY());
        }
    }

    /**
     * 绘制对比图连续渐变图例
     * <p>
     * 水平渐变条 B优势(蓝) ← 持平(灰) → A优势(紫)，
     * 每个像素调用 {@link HeatmapColorScale#comparisonColor} 生成；
     * 标签标注 B优势、持平、A优势。
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    private static void drawComparisonLegend(Graphics2D g, HeatmapLayout layout) {
        LegendLabelContext ctx = drawLegendBar(g, layout, i -> HeatmapColorScale.comparisonColor(-1.0 + 2.0 * i));
        FontMetrics fm = ctx.fm();
        int labelY = ctx.labelY();

        String leftLabel = "B优势";
        String midLabel = "持平";
        String rightLabel = "A优势";
        g.drawString(leftLabel, ctx.barX(), labelY);
        int midX = ctx.barX() + ctx.barWidth() / 2 - fm.stringWidth(midLabel) / 2;
        g.drawString(midLabel, midX, labelY);
        int rightX = ctx.barX() + ctx.barWidth() - fm.stringWidth(rightLabel);
        g.drawString(rightLabel, rightX, labelY);
    }

    /**
     * 绘制水平渐变条（每个像素通过 colorFunction 计算颜色）
     *
     * @param g             图形上下文
     * @param barX          渐变条左上角 x
     * @param barY          渐变条左上角 y
     * @param barWidth      渐变条宽度
     * @param colorFunction 输入 [0,1] 归一化位置，返回对应颜色
     */
    private static void drawGradientBar(Graphics2D g, int barX, int barY, int barWidth,
                                        java.util.function.DoubleFunction<Color> colorFunction) {
        int barHeight = 12;
        for (int i = 0; i < barWidth; i++) {
            double ratio = (double) i / (barWidth - 1);
            g.setColor(colorFunction.apply(ratio));
            g.fillRect(barX + i, barY, 1, barHeight);
        }
        g.setColor(HeatmapColorScale.GRID_COLOR);
        g.drawRect(barX, barY, barWidth, barHeight);
    }

    // ==================== 工具方法 ====================

    /**
     * 格式化比例值为整数百分比文字
     *
     * @param ratio 比例值 [0, 1]
     * @return 百分比文字，如 "38%"
     */
    private static String formatPercent(double ratio) {
        return (int) Math.round(ratio * 100) + PERCENT;
    }
}
