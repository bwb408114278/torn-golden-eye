package pn.torn.goldeneye.torn.service.activity;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.torn.model.activity.ActivityComparisonHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.BaseActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.FactionActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;

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
 * 颜色、暗化与人数档位全部来自{@link HeatmapColorScale}。
 *
 * @author Bai
 * @version 1.5.0
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
    /**
     * 网格行数（周一..周日）
     */
    private static final int GRID_ROWS = 7;
    /**
     * 网格列数（0..23 时）
     */
    private static final int GRID_COLS = 24;

    /**
     * 图片总宽度
     */
    private static final int IMAGE_WIDTH = PADDING + ROW_LABEL_WIDTH + GRID_COLS * CELL_SIZE + PADDING;

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
     * 网格区左侧 X
     */
    private static final int GRID_X = PADDING + ROW_LABEL_WIDTH;
    /**
     * 网格区总宽度
     */
    private static final int GRID_WIDTH = GRID_COLS * CELL_SIZE;

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
     * 行标签：周一..周日
     */
    private static final String[] DAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
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
     * 动态布局：副标题行数决定时间轴、网格与图例的纵向位置
     *
     * @param imageHeight 图片总高度
     * @param timeAxisY   时间轴区顶部 Y
     * @param gridY       网格区顶部 Y
     * @param legendY     图例区顶部 Y
     */
    private record HeatmapLayout(
            int imageHeight,
            int timeAxisY,
            int gridY,
            int legendY) {
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
                drawTitle(g, vo.getTitle());
                drawSubtitleLines(g, vo);
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
                drawTitle(g, vo.getTitle());
                drawSubtitleLines(g, vo);
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
                drawTitle(g, COMPARISON_TITLE);
                drawSubtitleLines(g, vo);
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
     * 根据副标题行数计算动态布局
     *
     * @param vo 热力图数据
     * @return 动态布局
     */
    private static HeatmapLayout layoutFor(BaseActivityHeatmapVO vo) {
        int subtitleLineCount = hasNoticeLine(vo) ? 2 : 1;
        int timeAxisY = SUBTITLE_Y + subtitleLineCount * SUBTITLE_HEIGHT;
        int gridY = timeAxisY + TIME_AXIS_HEIGHT;
        int legendY = gridY + GRID_ROWS * CELL_SIZE;
        int imageHeight = legendY + LEGEND_HEIGHT + PADDING;
        return new HeatmapLayout(imageHeight, timeAxisY, gridY, legendY);
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
        return new BufferedImage(IMAGE_WIDTH, layout.imageHeight(), BufferedImage.TYPE_INT_RGB);
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
        g.fillRect(0, 0, IMAGE_WIDTH, image.getHeight());
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
     * 绘制标题（垂直居中于标题区）
     *
     * @param g     图形上下文
     * @param title 标题文字
     */
    private static void drawTitle(Graphics2D g, String title) {
        g.setFont(TITLE_FONT);
        g.setColor(HeatmapColorScale.TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        int x = (IMAGE_WIDTH - fm.stringWidth(title)) / 2;
        int baselineY = TITLE_Y + (TITLE_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(title, x, baselineY);
    }

    /**
     * 绘制两行副标题：第一行为指标/覆盖率说明，第二行（存在时）为数据不完整/legacy 提示
     *
     * @param g  图形上下文
     * @param vo 热力图数据
     */
    private static void drawSubtitleLines(Graphics2D g, BaseActivityHeatmapVO vo) {
        String subtitle = resolveSubtitleLine1(vo);
        if (subtitle != null && !subtitle.isBlank()) {
            drawSubtitleLine(g, subtitle, SUBTITLE_Y, HeatmapColorScale.SUB_TEXT_COLOR);
        }
        if (hasNoticeLine(vo)) {
            drawSubtitleLine(g, vo.getNoticeMessage(), SUBTITLE_Y + SUBTITLE_HEIGHT, NOTICE_COLOR);
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
     * @param g     图形上下文
     * @param text  文字
     * @param lineY 该行顶部 Y
     * @param color 文字颜色
     */
    private static void drawSubtitleLine(Graphics2D g, String text, int lineY, Color color) {
        g.setFont(SUBTITLE_FONT);
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        int x = (IMAGE_WIDTH - fm.stringWidth(text)) / 2;
        int baselineY = lineY + (SUBTITLE_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(text, x, baselineY);
    }

    /**
     * 绘制时间轴：0-23 小时标签，每 6 小时使用主文字色高亮
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    private static void drawTimeAxis(Graphics2D g, HeatmapLayout layout) {
        g.setFont(HEADER_FONT);
        FontMetrics fm = g.getFontMetrics();
        int baselineY = layout.timeAxisY() + (TIME_AXIS_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
        for (int h = 0; h < GRID_COLS; h++) {
            String label = String.valueOf(h);
            int x = GRID_X + h * CELL_SIZE + (CELL_SIZE - fm.stringWidth(label)) / 2;
            g.setColor(h % 6 == 0 ? HeatmapColorScale.TEXT_COLOR : HeatmapColorScale.SUB_TEXT_COLOR);
            g.drawString(label, x, baselineY);
        }
    }

    /**
     * 绘制行标签：周一..周日
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    private static void drawRowLabels(Graphics2D g, HeatmapLayout layout) {
        g.setFont(LABEL_FONT);
        g.setColor(HeatmapColorScale.TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        for (int dow = 0; dow < GRID_ROWS; dow++) {
            String label = DAY_LABELS[dow];
            int x = PADDING + (ROW_LABEL_WIDTH - fm.stringWidth(label)) / 2;
            int baselineY = layout.gridY() + dow * CELL_SIZE + (CELL_SIZE + fm.getAscent() - fm.getDescent()) / 2;
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
        for (int dow = 0; dow <= GRID_ROWS; dow++) {
            int y = layout.gridY() + dow * CELL_SIZE;
            g.drawLine(GRID_X, y, GRID_X + GRID_WIDTH, y);
        }
        for (int h = 0; h <= GRID_COLS; h++) {
            int x = GRID_X + h * CELL_SIZE;
            g.drawLine(x, layout.gridY(), x, layout.gridY() + GRID_ROWS * CELL_SIZE);
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
        int x = (IMAGE_WIDTH - fm.stringWidth(message)) / 2;
        int y = layout.imageHeight() / 2;
        g.drawString(message, x, y);
    }

    // ==================== 个人图格子 ====================

    /**
     * 绘制个人图 7×24 网格
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
        for (int dow = 0; dow < GRID_ROWS; dow++) {
            for (int h = 0; h < GRID_COLS; h++) {
                int x = GRID_X + h * CELL_SIZE;
                int y = layout.gridY() + dow * CELL_SIZE;
                if (observed[dow][h] == 0) {
                    drawEmptyCell(g, x, y);
                } else {
                    double rate = activeRate[dow][h];
                    Color cellColor = HeatmapColorScale.darkenedActivityColor(rate, idleRatio[dow][h]);
                    String text = formatPercent(rate);
                    drawCell(g, x, y, text, cellColor, HeatmapColorScale.textColorFor(cellColor));
                }
            }
        }
        drawGridLines(g, layout);
    }

    // ==================== 帮派图格子 ====================

    /**
     * 绘制帮派图 7×24 网格
     * <p>
     * 格内显示平均有效活跃人数（单一数字）；颜色为人数 5 档主色按 idleRatio 连续暗化，
     * I 不改变格内数字与人数档位；已观测且有效活跃为 0 时仍使用档位 0 主色（含暗化），
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
        for (int dow = 0; dow < GRID_ROWS; dow++) {
            for (int h = 0; h < GRID_COLS; h++) {
                int x = GRID_X + h * CELL_SIZE;
                int y = layout.gridY() + dow * CELL_SIZE;
                if (observed[dow][h] == 0) {
                    drawEmptyCell(g, x, y);
                } else {
                    Color cellColor = HeatmapColorScale.factionColor(
                            averageActiveCount[dow][h], idleRatio[dow][h]);
                    String text = String.valueOf((int) Math.round(averageActiveCount[dow][h]));
                    drawCell(g, x, y, text, cellColor, HeatmapColorScale.textColorFor(cellColor));
                }
            }
        }
        drawGridLines(g, layout);
    }

    // ==================== 对比图格子 ====================

    /**
     * 绘制对比图 7×24 网格
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

        for (int dow = 0; dow < GRID_ROWS; dow++) {
            for (int h = 0; h < GRID_COLS; h++) {
                int x = GRID_X + h * CELL_SIZE;
                int y = layout.gridY() + dow * CELL_SIZE;
                if (!bothObserved[dow][h]) {
                    // 无数据格：EMPTY_COLOR，不显示文字
                    drawCell(g, x, y, null, HeatmapColorScale.EMPTY_COLOR, HeatmapColorScale.NO_DATA_SYMBOL_COLOR);
                    continue;
                }
                double diff = f1[dow][h] - f2[dow][h];
                Color cellColor;
                if (useNeutral) {
                    cellColor = HeatmapColorScale.COMPARISON_NEUTRAL_COLOR;
                } else {
                    double normalized = HeatmapColorScale.normalizeComparisonDiff(diff, scale);
                    cellColor = HeatmapColorScale.comparisonColor(normalized);
                }
                String text = formatComparisonCell(f1[dow][h], f2[dow][h]);
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
        for (int dow = 0; dow < GRID_ROWS; dow++) {
            for (int h = 0; h < GRID_COLS; h++) {
                if (bothObserved[dow][h]) {
                    absDiffs.add(Math.abs(f1[dow][h] - f2[dow][h]));
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
     * 绘制个人图连续渐变图例
     * <p>
     * 水平渐变条，每个像素调用 {@link HeatmapColorScale#activityColor} 生成；
     * 刻度标注 0%、25%、50%、75%、100%。
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    private static void drawActivityLegend(Graphics2D g, HeatmapLayout layout) {
        int barX = GRID_X;
        int barWidth = GRID_WIDTH;
        int barY = layout.legendY() + 6;
        drawGradientBar(g, barX, barY, barWidth, HeatmapColorScale::activityColor);

        g.setFont(LABEL_FONT);
        g.setColor(HeatmapColorScale.SUB_TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        int labelY = barY + 12 + fm.getAscent() + 2;
        for (int tick : LEGEND_TICKS) {
            int tickX = barX + (int) Math.round(tick / 100.0 * barWidth);
            String label = tick + PERCENT;
            int labelWidth = fm.stringWidth(label);
            int lx = switch (tick) {
                case 0 -> tickX;
                case 100 -> tickX - labelWidth;
                default -> tickX - labelWidth / 2;
            };
            g.drawString(label, lx, labelY);
        }
    }

    /**
     * 绘制帮派图 5 档离散图例
     * <p>
     * 5 个等宽色块使用档位主色，标签为 0/25/50/75/100+。
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    private static void drawFactionLegend(Graphics2D g, HeatmapLayout layout) {
        int barX = GRID_X;
        int barWidth = GRID_WIDTH;
        int barY = layout.legendY() + 6;
        int blockWidth = barWidth / HeatmapColorScale.FACTION_TIER_MAIN.length;

        for (int tier = 0; tier < HeatmapColorScale.FACTION_TIER_MAIN.length; tier++) {
            g.setColor(HeatmapColorScale.FACTION_TIER_MAIN[tier]);
            g.fillRect(barX + tier * blockWidth, barY, blockWidth, 12);
        }
        g.setColor(HeatmapColorScale.GRID_COLOR);
        g.drawRect(barX, barY, barWidth, 12);

        g.setFont(LABEL_FONT);
        g.setColor(HeatmapColorScale.SUB_TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        int labelY = barY + 12 + fm.getAscent() + 2;
        for (int tier = 0; tier < HeatmapColorScale.FACTION_TIER_LABELS.length; tier++) {
            String label = HeatmapColorScale.FACTION_TIER_LABELS[tier];
            int centerX = barX + tier * blockWidth + blockWidth / 2;
            g.drawString(label, centerX - fm.stringWidth(label) / 2, labelY);
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
        int barX = GRID_X;
        int barWidth = GRID_WIDTH;
        int barY = layout.legendY() + 6;
        drawGradientBar(g, barX, barY, barWidth, i -> HeatmapColorScale.comparisonColor(-1.0 + 2.0 * i));

        g.setFont(LABEL_FONT);
        g.setColor(HeatmapColorScale.SUB_TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        int labelY = barY + 12 + fm.getAscent() + 2;

        String leftLabel = "B优势";
        String midLabel = "持平";
        String rightLabel = "A优势";
        g.drawString(leftLabel, barX, labelY);
        int midX = barX + barWidth / 2 - fm.stringWidth(midLabel) / 2;
        g.drawString(midLabel, midX, labelY);
        int rightX = barX + barWidth - fm.stringWidth(rightLabel);
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
