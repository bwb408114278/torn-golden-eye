package pn.torn.goldeneye.utils.image;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.base.exception.BizException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;

/**
 * 文本图片转换工具类
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class TextImageUtils {
    /**
     * 文本对齐方式枚举
     */
    public enum TextAlignment {LEFT, CENTER, RIGHT}

    /**
     * 文本渲染配置类
     */
    public static class TextConfig {
        private Font font = new Font("微软雅黑", Font.PLAIN, 14);
        private Color textColor = Color.BLACK;
        private Color bgColor = Color.WHITE;
        private TextAlignment alignment = TextAlignment.LEFT;
        private int horizontalPadding = 20;
        private int verticalPadding = 15;
        private int lineSpacing = 8;
        private int width = 0;

        public TextConfig setFont(Font font) {
            this.font = font;
            return this;
        }

        public TextConfig setTextColor(Color color) {
            this.textColor = color;
            return this;
        }

        public TextConfig setBgColor(Color color) {
            this.bgColor = color;
            return this;
        }

        public TextConfig setAlignment(TextAlignment alignment) {
            this.alignment = alignment;
            return this;
        }

        public TextConfig setHorizontalPadding(int padding) {
            this.horizontalPadding = padding;
            return this;
        }

        public TextConfig setVerticalPadding(int padding) {
            this.verticalPadding = padding;
            return this;
        }

        public TextConfig setLineSpacing(int spacing) {
            this.lineSpacing = spacing;
            return this;
        }

        public TextConfig setWidth(int width) {
            this.width = width;
            return this;
        }
    }

    /**
     * 渲染图片到Base64
     */
    public static String renderTextToBase64(String text) {
        return renderTextToBase64(text, new TextConfig());
    }

    /**
     * 渲染图片到Base64
     */
    public static String renderTextToBase64(String text, TextConfig config) {
        BufferedImage image = renderTextToImage(text, config);
        return convertToBase64(image);
    }

    /**
     * 渲染文本到图片（不包含特殊配置）
     */
    public static BufferedImage renderTextToImage(String text) {
        return renderTextToImage(text, new TextConfig());
    }

    /**
     * 渲染文本到图片
     */
    public static BufferedImage renderTextToImage(String text, TextConfig config) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Text cannot be empty");
        }
        // 用临时 Graphics 计算尺寸
        BufferedImage tempImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D tempG = tempImg.createGraphics();
        try {
            return doRender(text, config, tempG);
        } finally {
            tempG.dispose();
        }
    }

    /**
     * 执行渲染
     */
    private static BufferedImage doRender(String text, TextConfig config, Graphics2D tempG) {
        FontMetrics metrics = tempG.getFontMetrics(config.font);
        String[] lines = text.split("\n");
        int lineHeight = metrics.getHeight();
        // width <= 0 时自动计算内容宽度
        int actualWidth = config.width > 0 ? config.width : calculateAutoWidth(lines, metrics, config);
        int contentHeight = lines.length * lineHeight + (lines.length - 1) * config.lineSpacing;
        int totalHeight = contentHeight + 2 * config.verticalPadding;
        BufferedImage image = new BufferedImage(actualWidth, totalHeight, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = image.createGraphics();
        try {
            configureGraphics(g, config, image);
            drawLines(g, lines, config, metrics, lineHeight);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * 自动计算宽度
     */
    private static int calculateAutoWidth(String[] lines, FontMetrics metrics, TextConfig config) {
        int maxLineWidth = Arrays.stream(lines)
                .mapToInt(metrics::stringWidth)
                .max()
                .orElse(0);
        return maxLineWidth + 2 * config.horizontalPadding;
    }

    /**
     * 图片配置
     */
    private static void configureGraphics(Graphics2D g, TextConfig config, BufferedImage image) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(config.bgColor);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setFont(config.font);
        g.setColor(config.textColor);
    }

    /**
     * 绘制所有行
     */
    private static void drawLines(Graphics2D g, String[] lines, TextConfig config,
                                  FontMetrics metrics, int lineHeight) {
        int maxWidth = config.width - 2 * config.horizontalPadding;
        int currentY = config.verticalPadding + metrics.getAscent();
        for (String line : lines) {
            int x = calculateLineX(config, metrics, line, maxWidth);
            g.drawString(line, x, currentY);
            currentY += lineHeight + config.lineSpacing;
        }
    }

    /**
     * 计算行的X坐标
     */
    private static int calculateLineX(TextConfig config, FontMetrics metrics, String line, int maxWidth) {
        int textWidth = metrics.stringWidth(line);
        return switch (config.alignment) {
            case CENTER -> config.horizontalPadding + (maxWidth - textWidth) / 2;
            case RIGHT -> config.horizontalPadding + maxWidth - textWidth;
            default -> config.horizontalPadding;
        };
    }

    /**
     * 转换为Base64
     */
    private static String convertToBase64(BufferedImage image) {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", stream);
            return Base64.getEncoder().encodeToString(stream.toByteArray());
        } catch (Exception e) {
            throw new BizException("图片转Base64异常", e);
        }
    }
}