package pn.torn.goldeneye.torn.service.activity.render;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.torn.model.activity.ActivityComparisonHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.FactionActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 活跃度热力图网格区绘制器
 * <p>
 * 负责时间轴、行标签、网格线与个人/帮派/对比三种格子的绘制。行列数与行列标签一律取自
 * {@link HeatmapLayout#grid()}，本类不做"槽 → 行列"换算；颜色、暗化、无数据格与对比图
 * P95 差值算法全部沿用{@link HeatmapColorScale}的既有实现。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.10.03
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class HeatmapGridPainter {

    /**
     * 时间轴高亮间隔（每 6 列使用主文字色）
     */
    private static final int TIME_AXIS_HIGHLIGHT_STEP = 6;

    /**
     * 绘制时间轴：列标签由口径网格提供，每 6 列使用主文字色高亮
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    static void drawTimeAxis(Graphics2D g, HeatmapLayout layout) {
        g.setFont(HeatmapRenderStyle.HEADER_FONT);
        FontMetrics fm = g.getFontMetrics();
        int baselineY = layout.timeAxisY()
                + (HeatmapLayout.TIME_AXIS_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
        for (int col = 0; col < layout.grid().cols(); col++) {
            String label = layout.grid().colLabel(col);
            int x = cellX(col) + (HeatmapLayout.CELL_SIZE - fm.stringWidth(label)) / 2;
            g.setColor(col % TIME_AXIS_HIGHLIGHT_STEP == 0
                    ? HeatmapColorScale.TEXT_COLOR : HeatmapColorScale.SUB_TEXT_COLOR);
            g.drawString(label, x, baselineY);
        }
    }

    /**
     * 绘制行标签，文案由口径网格提供
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    static void drawRowLabels(Graphics2D g, HeatmapLayout layout) {
        g.setFont(HeatmapRenderStyle.LABEL_FONT);
        g.setColor(HeatmapColorScale.TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        for (int row = 0; row < layout.grid().rows(); row++) {
            String label = layout.grid().rowLabel(row);
            int x = HeatmapLayout.PADDING + (HeatmapLayout.ROW_LABEL_WIDTH - fm.stringWidth(label)) / 2;
            int baselineY = cellY(layout, row) + (HeatmapLayout.CELL_SIZE + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(label, x, baselineY);
        }
    }

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
    static void drawPersonalGrid(Graphics2D g, PersonalActivityHeatmapVO vo, HeatmapLayout layout) {
        double[][] activeRate = vo.getActiveRate();
        double[][] idleRatio = vo.getIdleRatio();
        int[][] observed = vo.getObservedSamples();
        for (int row = 0; row < layout.grid().rows(); row++) {
            for (int col = 0; col < layout.grid().cols(); col++) {
                if (observed[row][col] == 0) {
                    drawEmptyCell(g, layout, row, col);
                } else {
                    double rate = activeRate[row][col];
                    Color cellColor = HeatmapColorScale.darkenedActivityColor(rate, idleRatio[row][col]);
                    drawCell(g, layout, row, col, formatPercent(rate), cellColor);
                }
            }
        }
        drawGridLines(g, layout);
    }

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
    static void drawFactionGrid(Graphics2D g, FactionActivityHeatmapVO vo, HeatmapLayout layout) {
        double[][] averageActiveCount = vo.getAverageOnlineCount();
        double[][] idleRatio = vo.getIdleRatio();
        int[][] observed = vo.getObservedSamples();
        for (int row = 0; row < layout.grid().rows(); row++) {
            for (int col = 0; col < layout.grid().cols(); col++) {
                if (observed[row][col] == 0) {
                    drawEmptyCell(g, layout, row, col);
                } else {
                    Color cellColor = HeatmapColorScale.factionColor(
                            averageActiveCount[row][col], idleRatio[row][col]);
                    String text = String.valueOf((int) Math.round(averageActiveCount[row][col]));
                    drawCell(g, layout, row, col, text, cellColor);
                }
            }
        }
        drawGridLines(g, layout);
    }

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
    static void drawComparisonGrid(Graphics2D g, ActivityComparisonHeatmapVO vo, HeatmapLayout layout) {
        double[][] f1 = vo.getFaction1AverageOnline();
        double[][] f2 = vo.getFaction2AverageOnline();
        boolean[][] bothObserved = vo.getBothObserved();

        double scale = calculateComparisonScale(f1, f2, bothObserved);
        boolean useNeutral = scale == 0;

        for (int row = 0; row < layout.grid().rows(); row++) {
            for (int col = 0; col < layout.grid().cols(); col++) {
                if (!bothObserved[row][col]) {
                    // 无数据格：EMPTY_COLOR，不显示文字
                    drawCell(g, layout, row, col, null, HeatmapColorScale.EMPTY_COLOR);
                    continue;
                }
                Color cellColor = resolveComparisonColor(f1[row][col] - f2[row][col], scale, useNeutral);
                String text = formatComparisonCell(f1[row][col], f2[row][col]);
                drawCell(g, layout, row, col, text, cellColor);
            }
        }
        drawGridLines(g, layout);
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
            int y = cellY(layout, row);
            g.drawLine(HeatmapLayout.GRID_X, y, HeatmapLayout.GRID_X + layout.gridWidth(), y);
        }
        for (int col = 0; col <= layout.grid().cols(); col++) {
            int x = cellX(col);
            g.drawLine(x, layout.gridY(), x, cellY(layout, rows));
        }
    }

    /**
     * 绘制单个格子（背景色 + 居中文字）
     *
     * @param g         图形上下文
     * @param layout    动态布局
     * @param row       行号
     * @param col       列号
     * @param text      格内文字（null 或空串表示不绘制文字）
     * @param cellColor 格子背景色
     */
    private static void drawCell(Graphics2D g, HeatmapLayout layout, int row, int col,
                                 String text, Color cellColor) {
        int x = cellX(col);
        int y = cellY(layout, row);
        g.setColor(cellColor);
        g.fillRect(x, y, HeatmapLayout.CELL_SIZE, HeatmapLayout.CELL_SIZE);
        if (text != null && !text.isEmpty()) {
            drawCenteredText(g, x, y, text, HeatmapColorScale.textColorFor(cellColor));
        }
    }

    /**
     * 绘制无数据格子，显示 "-" 符号
     *
     * @param g      图形上下文
     * @param layout 动态布局
     * @param row    行号
     * @param col    列号
     */
    private static void drawEmptyCell(Graphics2D g, HeatmapLayout layout, int row, int col) {
        int x = cellX(col);
        int y = cellY(layout, row);
        g.setColor(HeatmapColorScale.EMPTY_COLOR);
        g.fillRect(x, y, HeatmapLayout.CELL_SIZE, HeatmapLayout.CELL_SIZE);
        drawCenteredText(g, x, y, HeatmapRenderStyle.NO_DATA_SYMBOL, HeatmapColorScale.NO_DATA_SYMBOL_COLOR);
    }

    /**
     * 在格子内绘制居中文字
     *
     * @param g         图形上下文
     * @param x         格子左上角 x 坐标
     * @param y         格子左上角 y 坐标
     * @param text      文字
     * @param textColor 文字颜色
     */
    private static void drawCenteredText(Graphics2D g, int x, int y, String text, Color textColor) {
        g.setFont(HeatmapRenderStyle.CELL_FONT);
        g.setColor(textColor);
        FontMetrics fm = g.getFontMetrics();
        int tx = x + (HeatmapLayout.CELL_SIZE - fm.stringWidth(text)) / 2;
        int ty = y + (HeatmapLayout.CELL_SIZE + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(text, tx, ty);
    }

    /**
     * 计算对比格子的背景色
     *
     * @param diff       双方平均有效活跃人数之差
     * @param scale      P95 差值刻度
     * @param useNeutral 刻度为 0 时统一使用中性色
     * @return 格子背景色
     */
    private static Color resolveComparisonColor(double diff, double scale, boolean useNeutral) {
        if (useNeutral) {
            return HeatmapColorScale.COMPARISON_NEUTRAL_COLOR;
        }
        return HeatmapColorScale.comparisonColor(HeatmapColorScale.normalizeComparisonDiff(diff, scale));
    }

    /**
     * 计算对比图 P95 scale
     * <p>
     * 收集所有 bothObserved=true 格子的 abs(diff)，排序后取第 95 百分位。
     * 若共同有效格子数 &lt;= 1，scale = abs(那个值)（空列表返回 0）。
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

    /**
     * 格式化比例值为整数百分比文字
     *
     * @param ratio 比例值 [0, 1]
     * @return 百分比文字，如 "38%"
     */
    private static String formatPercent(double ratio) {
        return (int) Math.round(ratio * 100) + HeatmapRenderStyle.PERCENT;
    }

    /**
     * 列号 → 格子左上角 X
     * <p>
     * 左边界只由外边距与行标签宽度决定，与网格列数无关，故无需布局参数。
     *
     * @param col 列号
     * @return X 坐标
     */
    private static int cellX(int col) {
        return HeatmapLayout.GRID_X + col * HeatmapLayout.CELL_SIZE;
    }

    /**
     * 行号 → 格子左上角 Y
     *
     * @param layout 动态布局
     * @param row    行号
     * @return Y 坐标
     */
    private static int cellY(HeatmapLayout layout, int row) {
        return layout.gridY() + row * HeatmapLayout.CELL_SIZE;
    }
}
