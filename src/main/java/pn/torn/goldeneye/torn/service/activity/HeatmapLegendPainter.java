package pn.torn.goldeneye.torn.service.activity;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.awt.*;
import java.util.function.DoubleFunction;

/**
 * 活跃度热力图图例区绘制器
 * <p>
 * 负责个人图连续比例图例、帮派图人数锚点图例与对比图发散图例。渐变条宽度取自
 * {@link HeatmapLayout#gridWidth()}，与网格区左右对齐；取色一律委派{@link HeatmapColorScale}，
 * 本类不内联任何 RGB。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.10.03
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class HeatmapLegendPainter {

    /**
     * 渐变条高度
     */
    private static final int BAR_HEIGHT = 12;
    /**
     * 渐变条顶部相对图例区顶部的偏移
     */
    private static final int BAR_OFFSET_Y = 6;

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
     * 绘制个人图连续渐变图例
     * <p>
     * 水平渐变条，每个像素调用 {@link HeatmapColorScale#activityColor} 生成；
     * 刻度标注 0%、25%、50%、75%、100%。
     *
     * @param g      图形上下文
     * @param layout 动态布局
     */
    static void drawActivityLegend(Graphics2D g, HeatmapLayout layout) {
        LegendLabelContext ctx = drawLegendBar(g, layout, HeatmapColorScale::activityColor);
        FontMetrics fm = ctx.fm();
        for (int tick : HeatmapRenderStyle.LEGEND_TICKS) {
            int tickX = ctx.barX() + (int) Math.round(tick / 100.0 * ctx.barWidth());
            String label = tick + HeatmapRenderStyle.PERCENT;
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
    static void drawFactionLegend(Graphics2D g, HeatmapLayout layout) {
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
    static void drawComparisonLegend(Graphics2D g, HeatmapLayout layout) {
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
     * 绘制图例渐变条并准备标签上下文（个人/帮派/对比图例共用前导）
     *
     * @param g             图形上下文
     * @param layout        动态布局
     * @param colorFunction 渐变条取色函数，输入 [0,1] 归一化位置
     * @return 标签绘制上下文
     */
    private static LegendLabelContext drawLegendBar(Graphics2D g, HeatmapLayout layout,
                                                    DoubleFunction<Color> colorFunction) {
        int barX = HeatmapLayout.GRID_X;
        int barWidth = layout.gridWidth();
        int barY = layout.legendY() + BAR_OFFSET_Y;
        drawGradientBar(g, barX, barY, barWidth, colorFunction);

        g.setFont(HeatmapRenderStyle.LABEL_FONT);
        g.setColor(HeatmapColorScale.SUB_TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        return new LegendLabelContext(barX, barWidth,
                barY + BAR_HEIGHT + fm.getAscent() + 2, fm);
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
                                        DoubleFunction<Color> colorFunction) {
        for (int i = 0; i < barWidth; i++) {
            double ratio = (double) i / (barWidth - 1);
            g.setColor(colorFunction.apply(ratio));
            g.fillRect(barX + i, barY, 1, BAR_HEIGHT);
        }
        g.setColor(HeatmapColorScale.GRID_COLOR);
        g.drawRect(barX, barY, barWidth, BAR_HEIGHT);
    }
}
