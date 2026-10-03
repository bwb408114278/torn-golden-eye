package pn.torn.goldeneye.torn.service.activity.render;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.base.exception.BizException;
import pn.torn.goldeneye.torn.model.activity.ActivityComparisonHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.BaseActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.FactionActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;
import pn.torn.goldeneye.torn.service.activity.ActivityHeatmapService;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * 活跃度热力图 PNG 图片渲染器
 * <p>
 * 负责个人活跃度热力图、帮派活跃度热力图和帮派活跃度对比图的 PNG 渲染，并统一编排标题、
 * 副标题、网格区与图例区。布局坐标由{@link HeatmapLayout}一次算出，网格区与图例区分别委派
 * {@link HeatmapGridPainter}与{@link HeatmapLegendPainter}，本类只保留画布、文案与编排，
 * 不再同时承担三种图元的绘制。副标题支持两行绘制（第一行指标/覆盖率说明，第二行数据不完整
 * 与 legacy 提示），存在第二行时布局高度相应增加，禁止文本重叠或截断。
 * 颜色、暗化与人数渐变锚点全部来自{@link HeatmapColorScale}。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.07.21
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HeatmapImageRenderer {

    /**
     * 对比图标题固定文案
     */
    private static final String COMPARISON_TITLE = "帮派活跃度对比";

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
        HeatmapLayout layout = HeatmapLayout.forVo(vo, hasNoticeLine(vo));
        BufferedImage image = createCanvas(layout);
        Graphics2D g = image.createGraphics();
        try {
            applyRenderingHints(g, image);
            if (vo.isHasData()) {
                drawTitle(g, layout, vo.getTitle());
                drawSubtitleLines(g, layout, vo);
                HeatmapGridPainter.drawTimeAxis(g, layout);
                HeatmapGridPainter.drawRowLabels(g, layout);
                HeatmapGridPainter.drawPersonalGrid(g, vo, layout);
                HeatmapLegendPainter.drawActivityLegend(g, layout);
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
        HeatmapLayout layout = HeatmapLayout.forVo(vo, hasNoticeLine(vo));
        BufferedImage image = createCanvas(layout);
        Graphics2D g = image.createGraphics();
        try {
            applyRenderingHints(g, image);
            if (vo.isHasData()) {
                drawTitle(g, layout, vo.getTitle());
                drawSubtitleLines(g, layout, vo);
                HeatmapGridPainter.drawTimeAxis(g, layout);
                HeatmapGridPainter.drawRowLabels(g, layout);
                HeatmapGridPainter.drawFactionGrid(g, vo, layout);
                HeatmapLegendPainter.drawFactionLegend(g, layout);
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
        HeatmapLayout layout = HeatmapLayout.forVo(vo, hasNoticeLine(vo));
        BufferedImage image = createCanvas(layout);
        Graphics2D g = image.createGraphics();
        try {
            applyRenderingHints(g, image);
            if (vo.isHasData()) {
                drawTitle(g, layout, COMPARISON_TITLE);
                drawSubtitleLines(g, layout, vo);
                HeatmapGridPainter.drawTimeAxis(g, layout);
                HeatmapGridPainter.drawRowLabels(g, layout);
                HeatmapGridPainter.drawComparisonGrid(g, vo, layout);
                HeatmapLegendPainter.drawComparisonLegend(g, layout);
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

    // ==================== 标题与副标题 ====================

    /**
     * 绘制标题（水平居中于图片、垂直居中于标题区）
     *
     * @param g      图形上下文
     * @param layout 动态布局
     * @param title  标题文字
     */
    private static void drawTitle(Graphics2D g, HeatmapLayout layout, String title) {
        g.setFont(HeatmapRenderStyle.TITLE_FONT);
        g.setColor(HeatmapColorScale.TEXT_COLOR);
        FontMetrics fm = g.getFontMetrics();
        int x = (layout.imageWidth() - fm.stringWidth(title)) / 2;
        int baselineY = HeatmapLayout.TITLE_Y
                + (HeatmapLayout.TITLE_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
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
            drawSubtitleLine(g, layout, subtitle, HeatmapLayout.SUBTITLE_Y, HeatmapColorScale.SUB_TEXT_COLOR);
        }
        if (hasNoticeLine(vo)) {
            drawSubtitleLine(g, layout, vo.getNoticeMessage(),
                    HeatmapLayout.SUBTITLE_Y + HeatmapLayout.SUBTITLE_HEIGHT, HeatmapRenderStyle.NOTICE_COLOR);
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
                    : "有效采样覆盖率: " + (int) Math.round(vo.getCoverage() * 100)
                    + HeatmapRenderStyle.PERCENT;
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
        g.setFont(HeatmapRenderStyle.SUBTITLE_FONT);
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        int x = (layout.imageWidth() - fm.stringWidth(text)) / 2;
        int baselineY = lineY + (HeatmapLayout.SUBTITLE_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(text, x, baselineY);
    }

    /**
     * 绘制无数据整图提示（居中橙色文字），防御性保留给 hasData=false 的调用
     *
     * @param g       图形上下文
     * @param layout  动态布局
     * @param message 提示信息
     */
    private static void drawInsufficientMessage(Graphics2D g, HeatmapLayout layout, String message) {
        g.setFont(HeatmapRenderStyle.TITLE_FONT);
        g.setColor(HeatmapRenderStyle.NOTICE_COLOR);
        FontMetrics fm = g.getFontMetrics();
        int x = (layout.imageWidth() - fm.stringWidth(message)) / 2;
        int y = layout.imageHeight() / 2;
        g.drawString(message, x, y);
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
}
