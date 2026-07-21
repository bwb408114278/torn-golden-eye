package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热力图颜色渐变与渲染测试
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.21
 */
@DisplayName("热力图颜色渐变与渲染测试")
class HeatmapImageRendererTest {

    @Test
    @DisplayName("activityColor(0) 应返回渐变起点色 (68,1,84)")
    void shouldReturnFirstAnchorColorForZeroRatio() {
        Color c = HeatmapColorScale.activityColor(0);
        assertEquals(new Color(68, 1, 84), c);
    }

    @Test
    @DisplayName("activityColor(1) 应返回渐变终点色 (253,231,37)")
    void shouldReturnLastAnchorColorForOneRatio() {
        Color c = HeatmapColorScale.activityColor(1);
        assertEquals(new Color(253, 231, 37), c);
    }

    @Test
    @DisplayName("activityColor 超出 [0,1] 应被 clamp 到锚点色")
    void shouldClampActivityColorOutOfRange() {
        assertEquals(HeatmapColorScale.activityColor(0), HeatmapColorScale.activityColor(-0.5));
        assertEquals(HeatmapColorScale.activityColor(1), HeatmapColorScale.activityColor(1.5));
    }

    @Test
    @DisplayName("comparisonColor(-1) 应返回 B 方强优势色 (33,102,172)")
    void shouldReturnBlueAnchorForNegativeOne() {
        Color c = HeatmapColorScale.comparisonColor(-1);
        assertEquals(new Color(33, 102, 172), c);
    }

    @Test
    @DisplayName("comparisonColor(0) 应返回持平色 (242,242,242)")
    void shouldReturnNeutralColorForZero() {
        Color c = HeatmapColorScale.comparisonColor(0);
        assertEquals(new Color(242, 242, 242), c);
    }

    @Test
    @DisplayName("comparisonColor(1) 应返回 A 方强优势色 (118,42,131)")
    void shouldReturnPurpleAnchorForPositiveOne() {
        Color c = HeatmapColorScale.comparisonColor(1);
        assertEquals(new Color(118, 42, 131), c);
    }

    @Test
    @DisplayName("线性插值中点应等于两端 RGB 均值")
    void shouldLerpMidpointCorrectly() {
        Color c1 = new Color(0, 0, 0);
        Color c2 = new Color(100, 200, 50);
        Color mid = HeatmapColorScale.lerpColor(c1, c2, 0.5);
        assertEquals(50, mid.getRed());
        assertEquals(100, mid.getGreen());
        assertEquals(25, mid.getBlue());
    }

    @Test
    @DisplayName("深色背景应使用白色文字，浅色背景应使用深色文字")
    void shouldSelectTextColorByBackgroundLuminance() {
        assertEquals(Color.WHITE, HeatmapColorScale.textColorFor(new Color(30, 30, 30)));
        assertEquals(new Color(16, 16, 16), HeatmapColorScale.textColorFor(new Color(253, 231, 37)));
    }

    @Test
    @DisplayName("normalizeComparisonDiff 应将差值归一化到 [-1,1]")
    void shouldNormalizeDiffToRange() {
        double normalized = HeatmapColorScale.normalizeComparisonDiff(10, 20);
        assertEquals(0.5, normalized, 0.001);

        normalized = HeatmapColorScale.normalizeComparisonDiff(-10, 20);
        assertEquals(-0.5, normalized, 0.001);
    }

    @Test
    @DisplayName("normalizeComparisonDiff scale 为 0 时应使用最小安全值防止除零")
    void shouldUseMinSafetyWhenScaleIsZero() {
        double normalized = HeatmapColorScale.normalizeComparisonDiff(10, 0);
        assertTrue(normalized <= 1.0);
        assertTrue(normalized >= -1.0);
    }

    @Test
    @DisplayName("所有 ACTIVITY_GRADIENT 锚点颜色文字可读性验证")
    void shouldAllActivityGradientAnchorsHaveReadableText() {
        for (Color c : HeatmapColorScale.ACTIVITY_GRADIENT) {
            Color textColor = HeatmapColorScale.textColorFor(c);
            // 验证文字色与背景色有足够对比度
            double bgLum = c.getRed() * 0.299 + c.getGreen() * 0.587 + c.getBlue() * 0.114;
            double textLum = textColor.getRed() * 0.299 + textColor.getGreen() * 0.587 + textColor.getBlue() * 0.114;
            assertTrue(Math.abs(bgLum - textLum) > 50,
                    "锚点色 " + c + " 文字对比度不足");
        }
    }

    @Test
    @DisplayName("所有 COMPARISON_GRADIENT 锚点颜色文字可读性验证")
    void shouldAllComparisonGradientAnchorsHaveReadableText() {
        for (Color c : HeatmapColorScale.COMPARISON_GRADIENT) {
            Color textColor = HeatmapColorScale.textColorFor(c);
            double bgLum = c.getRed() * 0.299 + c.getGreen() * 0.587 + c.getBlue() * 0.114;
            double textLum = textColor.getRed() * 0.299 + textColor.getGreen() * 0.587 + textColor.getBlue() * 0.114;
            assertTrue(Math.abs(bgLum - textLum) > 50,
                    "对比锚点色 " + c + " 文字对比度不足");
        }
    }
}
