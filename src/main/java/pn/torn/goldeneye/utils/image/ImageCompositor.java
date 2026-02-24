package pn.torn.goldeneye.utils.image;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import pn.torn.goldeneye.base.exception.BizException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

/**
 * 图片合成工具类
 *
 * @author Bai
 * @version 0.5.0
 * @since 2026.02.24
 */
@NoArgsConstructor(access = AccessLevel.NONE)
public class ImageCompositor {
    /**
     * 垂直拼接多张图片，宽度不足时用白色填充对齐
     */
    public static BufferedImage stitchVertically(List<BufferedImage> images) {
        return stitchVertically(images, Color.WHITE);
    }

    /**
     * 垂直拼接多张图片
     */
    public static BufferedImage stitchVertically(List<BufferedImage> images, Color bgColor) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("Images cannot be empty");
        }
        int maxWidth = images.stream().mapToInt(BufferedImage::getWidth).max().orElse(0);
        int totalHeight = images.stream().mapToInt(BufferedImage::getHeight).sum();

        BufferedImage result = new BufferedImage(maxWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setColor(bgColor);
            g.fillRect(0, 0, maxWidth, totalHeight);
            int currentY = 0;
            for (BufferedImage img : images) {
                g.drawImage(img, 0, currentY, null);
                currentY += img.getHeight();
            }
        } finally {
            g.dispose();
        }
        return result;
    }

    /**
     * 垂直拼接图片并输出Base64
     */
    public static String stitchVerticallyToBase64(List<BufferedImage> images) {
        return convertToBase64(stitchVertically(images));
    }

    /**
     * 转换Base64
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