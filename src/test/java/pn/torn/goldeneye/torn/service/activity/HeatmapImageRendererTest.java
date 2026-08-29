package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.activity.FactionActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;

import javax.imageio.ImageIO;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 热力图颜色渐变、暗化与渲染测试
 * <p>
 * 固定帮派 0/25/50/75/100+ 五个渐变锚点主色、锚点间连续插值与 Idle 比例 0/50%/100%
 * 连续暗化 RGB，保留对比图色板回归；额外生成三张固定夹具 PNG 到测试 target/ 供人工视觉复核。
 *
 * @author Bai
 * @version 1.5.1
 * @since 2026.07.21
 */
@DisplayName("热力图颜色渐变、暗化与渲染测试")
class HeatmapImageRendererTest {

    // ==================== 个人图与对比图既有色板 ====================

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
    @DisplayName("comparisonColor 锚点回归：-1 蓝 / 0 灰 / 1 紫")
    void shouldReturnComparisonAnchorColors() {
        assertEquals(new Color(33, 102, 172), HeatmapColorScale.comparisonColor(-1));
        assertEquals(new Color(242, 242, 242), HeatmapColorScale.comparisonColor(0));
        assertEquals(new Color(118, 42, 131), HeatmapColorScale.comparisonColor(1));
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
        assertEquals(0.5, HeatmapColorScale.normalizeComparisonDiff(10, 20), 0.001);
        assertEquals(-0.5, HeatmapColorScale.normalizeComparisonDiff(-10, 20), 0.001);
        assertTrue(HeatmapColorScale.normalizeComparisonDiff(10, 0) <= 1.0);
        assertTrue(HeatmapColorScale.normalizeComparisonDiff(10, 0) >= -1.0);
    }

    @Test
    @DisplayName("所有 ACTIVITY_GRADIENT 锚点颜色文字可读性验证")
    void shouldAllActivityGradientAnchorsHaveReadableText() {
        for (Color c : HeatmapColorScale.ACTIVITY_GRADIENT) {
            assertReadableText(c, "锚点色");
        }
    }

    @Test
    @DisplayName("所有 COMPARISON_GRADIENT 锚点颜色文字可读性验证")
    void shouldAllComparisonGradientAnchorsHaveReadableText() {
        for (Color c : HeatmapColorScale.COMPARISON_GRADIENT) {
            assertReadableText(c, "对比锚点色");
        }
    }

    // ==================== 帮派图 5 锚点渐变主色 ====================

    @Test
    @DisplayName("帮派 5 个渐变锚点主色应为冻结 Viridis 锚点 RGB")
    void shouldReturnFrozenFactionAnchorColors() {
        assertEquals(new Color(68, 1, 84), HeatmapColorScale.factionColor(0, 0));
        assertEquals(new Color(59, 82, 139), HeatmapColorScale.factionColor(25, 0));
        assertEquals(new Color(33, 145, 140), HeatmapColorScale.factionColor(50, 0));
        assertEquals(new Color(94, 201, 98), HeatmapColorScale.factionColor(75, 0));
        assertEquals(new Color(253, 231, 37), HeatmapColorScale.factionColor(100, 0));
    }

    @Test
    @DisplayName("帮派主色在锚点间连续插值：A=12.5/37.5 为相邻锚点中点，越界 clamp 到首尾锚点")
    void shouldInterpolateFactionMainColorBetweenAnchors() {
        assertEquals(new Color(64, 42, 112), HeatmapColorScale.factionColor(12.5, 0));
        assertEquals(new Color(46, 114, 140), HeatmapColorScale.factionColor(37.5, 0));
        assertEquals(new Color(68, 1, 84), HeatmapColorScale.factionColor(-1, 0));
        assertEquals(new Color(253, 231, 37), HeatmapColorScale.factionColor(150, 0));
        assertEquals(new Color(253, 231, 37), HeatmapColorScale.factionMainColor(100));
    }

    @Test
    @DisplayName("插值主色同样参与 Idle 暗化：A=12.5、idle=1 为 (64,42,112)×0.55")
    void shouldDarkenInterpolatedFactionColor() {
        assertEquals(new Color(35, 23, 62), HeatmapColorScale.factionColor(12.5, 1));
    }

    @Test
    @DisplayName("帮派图例位置映射：[0,1] 对应人数锚点范围，两端为首尾锚点色")
    void shouldMapLegendPositionToAnchorRange() {
        assertEquals(HeatmapColorScale.factionMainColor(0), HeatmapColorScale.factionLegendColor(0));
        assertEquals(HeatmapColorScale.factionMainColor(50), HeatmapColorScale.factionLegendColor(0.5));
        assertEquals(HeatmapColorScale.factionMainColor(100), HeatmapColorScale.factionLegendColor(1));
        assertEquals(HeatmapColorScale.factionMainColor(100), HeatmapColorScale.factionLegendColor(1.5));
    }

    @Test
    @DisplayName("所有帮派锚点与锚点中点主色及最大暗化色文字可读性验证")
    void shouldAllFactionAnchorAndMidColorsHaveReadableText() {
        for (Color c : HeatmapColorScale.FACTION_GRADIENT) {
            assertReadableText(c, "帮派锚点主色");
            assertReadableText(HeatmapColorScale.darken(c, 1), "帮派最大暗化色");
        }
        for (int i = 0; i < HeatmapColorScale.FACTION_GRADIENT.length - 1; i++) {
            Color mid = HeatmapColorScale.lerpColor(HeatmapColorScale.FACTION_GRADIENT[i],
                    HeatmapColorScale.FACTION_GRADIENT[i + 1], 0.5);
            assertReadableText(mid, "帮派锚点中点色");
        }
    }

    // ==================== Idle 连续暗化 ====================

    @Test
    @DisplayName("idleRatio=100% 时各锚点均暗化到冻结最大暗化色（主色 × 0.55 四舍五入）")
    void shouldDarkenAllAnchorsToMaxDarkenedColorsAtFullIdle() {
        assertEquals(new Color(37, 1, 46), HeatmapColorScale.factionColor(0, 1));
        assertEquals(new Color(32, 45, 76), HeatmapColorScale.factionColor(25, 1));
        assertEquals(new Color(18, 80, 77), HeatmapColorScale.factionColor(50, 1));
        assertEquals(new Color(52, 111, 54), HeatmapColorScale.factionColor(75, 1));
        assertEquals(new Color(139, 127, 20), HeatmapColorScale.factionColor(100, 1));
    }

    @Test
    @DisplayName("idleRatio=50% 时按 ×0.775 连续暗化（档0 → 53,1,65）")
    void shouldDarkenContinuouslyAtHalfIdle() {
        assertEquals(new Color(53, 1, 65), HeatmapColorScale.factionColor(0, 0.5));

        for (Color main : HeatmapColorScale.FACTION_GRADIENT) {
            Color darkened = HeatmapColorScale.darken(main, 0.5);
            assertEquals((int) Math.round(main.getRed() * 0.775), darkened.getRed());
            assertEquals((int) Math.round(main.getGreen() * 0.775), darkened.getGreen());
            assertEquals((int) Math.round(main.getBlue() * 0.775), darkened.getBlue());
        }
    }

    @Test
    @DisplayName("idleRatio=0 使用完整主色；超出 [0,1] 被 clamp")
    void shouldKeepMainColorAtZeroIdleAndClampOverflow() {
        assertEquals(HeatmapColorScale.FACTION_GRADIENT[0], HeatmapColorScale.darken(
                HeatmapColorScale.FACTION_GRADIENT[0], 0));
        assertEquals(HeatmapColorScale.darken(HeatmapColorScale.FACTION_GRADIENT[1], 1),
                HeatmapColorScale.darken(HeatmapColorScale.FACTION_GRADIENT[1], 1.5));
        assertEquals(HeatmapColorScale.darken(HeatmapColorScale.FACTION_GRADIENT[2], 0),
                HeatmapColorScale.darken(HeatmapColorScale.FACTION_GRADIENT[2], -0.5));
    }

    @Test
    @DisplayName("个人图暗化：idleRatio=0 等于原比例色，主色不改变档位语义")
    void shouldDarkenPersonalActivityColorOnlyByIdleRatio() {
        assertEquals(HeatmapColorScale.activityColor(0.5),
                HeatmapColorScale.darkenedActivityColor(0.5, 0));
        assertEquals(new Color(139, 127, 20), HeatmapColorScale.darkenedActivityColor(1.0, 1.0));
    }

    @Test
    @DisplayName("无数据深灰格必须与首锚点主色区分")
    void shouldKeepEmptyColorDistinctFromFirstAnchor() {
        assertNotEquals(HeatmapColorScale.EMPTY_COLOR, HeatmapColorScale.FACTION_GRADIENT[0]);
        assertNotEquals(HeatmapColorScale.EMPTY_COLOR, HeatmapColorScale.factionColor(0, 1));
    }

    // ==================== 固定夹具 PNG（人工视觉复核，不提交） ====================

    @Test
    @DisplayName("生成三张固定夹具 PNG 到 target/heatmap-fixtures 供人工复核")
    void shouldRenderFixturePngsForManualReview() throws Exception {
        Path dir = Paths.get("target", "heatmap-fixtures");
        Files.createDirectories(dir);

        Path personal = dir.resolve("personal-v3-idle.png");
        assertTrue(ImageIO.write(HeatmapImageRenderer.renderPersonal(buildPersonalFixture()), "png",
                personal.toFile()));
        Path faction = dir.resolve("faction-main-dark.png");
        assertTrue(ImageIO.write(HeatmapImageRenderer.renderFaction(buildFactionFixture(null)), "png",
                faction.toFile()));
        Path legacy = dir.resolve("faction-legacy-mixed.png");
        assertTrue(ImageIO.write(HeatmapImageRenderer.renderFaction(
                        buildFactionFixture("该时间范围仅覆盖 3 个采样日，热力图仅供参考；部分历史采样未区分 Idle，仅供趋势参考")),
                "png", legacy.toFile()));

        for (Path path : List.of(personal, faction, legacy)) {
            assertTrue(Files.exists(path), "夹具 PNG 应存在: " + path);
            assertTrue(Files.size(path) > 0, "夹具 PNG 不应为空: " + path);
        }
    }

    /**
     * 个人 V3 Idle 夹具：一行内比例递增并伴随不同暗化，含无数据格
     */
    private static PersonalActivityHeatmapVO buildPersonalFixture() {
        PersonalActivityHeatmapVO vo = PersonalActivityHeatmapVO.empty("测试用户 [54321] 活跃度热力图");
        vo.setSubtitle("有效采样覆盖率: 62%");
        vo.setHasData(true);
        vo.setTotalDays(28);
        double[] rates = {0.05, 0.15, 0.3, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95, 1.0, 0.5, 0.2,
                0.4, 0.6, 0.7, 0.8, 0.35, 0.25, 0.1, 0.9, 0.5, 0.15, 0.65, 0.4};
        for (int h = 0; h < 24; h++) {
            vo.getObservedSamples()[0][h] = 4;
            vo.getActiveRate()[0][h] = rates[h];
            vo.getIdleRatio()[0][h] = h / 23.0;
        }
        for (int h = 0; h < 24; h += 2) {
            vo.getObservedSamples()[1][h] = 4;
            vo.getActiveRate()[1][h] = rates[(h + 5) % 24];
            vo.getIdleRatio()[1][h] = 0.3;
        }
        return vo;
    }

    /**
     * 帮派渐变/暗色夹具：一行覆盖 0~100+ 锚点渐变区间（含 12.5/37.5 插值点）与 0~1 连续暗化，含无数据格
     *
     * @param notice 副标题第二行提示，null 表示无提示
     */
    private static FactionActivityHeatmapVO buildFactionFixture(String notice) {
        FactionActivityHeatmapVO vo = FactionActivityHeatmapVO.empty("测试帮派 [20465] 活跃度热力图");
        vo.setSubtitle("格内：平均有效活跃人数｜颜色：有效活跃人数渐变，Idle 越多越暗｜有效采样覆盖率: 71%");
        vo.setNoticeMessage(notice);
        vo.setHasData(true);
        vo.setTotalDays(28);
        double[] averages = {0, 12.5, 25, 37.5, 50, 66, 75, 88, 100, 130, 45, 55,
                30, 70, 95, 110, 20, 60, 80, 120, 5, 35, 85, 105};
        for (int h = 0; h < 24; h++) {
            vo.getObservedSamples()[2][h] = 4;
            vo.getAverageOnlineCount()[2][h] = averages[h];
            vo.getIdleRatio()[2][h] = h / 23.0;
        }
        for (int h = 0; h < 24; h += 3) {
            vo.getObservedSamples()[3][h] = 4;
            vo.getAverageOnlineCount()[3][h] = averages[(h + 7) % 24];
            vo.getIdleRatio()[3][h] = 0.5;
        }
        return vo;
    }

    /**
     * 断言背景色与文字色有足够对比度
     */
    private static void assertReadableText(Color background, String label) {
        Color textColor = HeatmapColorScale.textColorFor(background);
        double bgLum = background.getRed() * 0.299 + background.getGreen() * 0.587 + background.getBlue() * 0.114;
        double textLum = textColor.getRed() * 0.299 + textColor.getGreen() * 0.587 + textColor.getBlue() * 0.114;
        assertTrue(Math.abs(bgLum - textLum) > 50, label + " " + background + " 文字对比度不足");
    }
}
