package pn.torn.goldeneye.torn.service.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.activity.ActivityCaliberEnum;
import pn.torn.goldeneye.torn.model.activity.FactionActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.PersonalActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.grid.ActivityGridLayout;
import pn.torn.goldeneye.torn.service.activity.render.HeatmapColorScale;
import pn.torn.goldeneye.torn.service.activity.render.HeatmapImageRenderer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热力图颜色渐变、暗化与渲染测试
 * <p>
 * 固定帮派 0/25/50/75/100+ 五个渐变锚点主色、锚点间连续插值与 Idle 比例 0/50%/100%
 * 连续暗化 RGB，保留对比图色板回归；额外生成三张固定夹具 PNG 到测试 target/ 供人工视觉复核。
 *
 * @author Bai
 * @version 1.7.0
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
    @DisplayName("个人图暗化：idleRatio=0 等于原比例色，主色不改变档位语义")
    void shouldDarkenPersonalActivityColorOnlyByIdleRatio() {
        assertEquals(HeatmapColorScale.activityColor(0.5),
                HeatmapColorScale.darkenedActivityColor(0.5, 0));
        assertEquals(new Color(139, 127, 20), HeatmapColorScale.darkenedActivityColor(1.0, 1.0));
    }

    // ==================== 固定夹具 PNG（人工视觉复核，不提交） ====================

    /**
     * 典型周与半月的夹具起始日
     */
    private static final LocalDate FIXTURE_ANCHOR = LocalDate.of(2026, 9, 22);
    /**
     * 单日夹具锚点日
     */
    private static final LocalDate SINGLE_DAY_ANCHOR = LocalDate.of(2026, 9, 28);

    @Test
    @DisplayName("生成用户图与帮派图 4×24 / 7×24 / 15×24 夹具 PNG 并校验图片尺寸")
    void shouldRenderFixturePngsForManualReview() throws Exception {
        Path dir = Paths.get("target", "heatmap-fixtures");
        Files.createDirectories(dir);

        assertFixture(dir.resolve("personal-single-day-4x24.png"),
                HeatmapImageRenderer.renderPersonal(buildPersonalFixture(ActivityCaliberEnum.SINGLE_DAY)), 292);
        assertFixture(dir.resolve("personal-typical-week-7x24.png"),
                HeatmapImageRenderer.renderPersonal(buildPersonalFixture(ActivityCaliberEnum.TYPICAL_WEEK)), 400);
        assertFixture(dir.resolve("personal-half-month-15x24.png"),
                HeatmapImageRenderer.renderPersonal(buildPersonalFixture(ActivityCaliberEnum.HALF_MONTH)), 688);
        assertFixture(dir.resolve("faction-single-day-4x24.png"),
                HeatmapImageRenderer.renderFaction(buildFactionFixture(ActivityCaliberEnum.SINGLE_DAY, null)), 292);
        assertFixture(dir.resolve("faction-typical-week-7x24.png"),
                HeatmapImageRenderer.renderFaction(buildFactionFixture(ActivityCaliberEnum.TYPICAL_WEEK, null)), 400);
        assertFixture(dir.resolve("faction-half-month-15x24.png"),
                HeatmapImageRenderer.renderFaction(buildFactionFixture(ActivityCaliberEnum.HALF_MONTH, null)), 688);
        assertFixture(dir.resolve("faction-typical-week-7x24-notice.png"),
                HeatmapImageRenderer.renderFaction(buildFactionFixture(ActivityCaliberEnum.TYPICAL_WEEK,
                        "该时间范围仅覆盖 3 个采样日，热力图仅供参考；部分历史采样未区分 Idle，仅供趋势参考")), 420);
    }

    /**
     * 写出夹具 PNG 并校验固定尺寸：宽度恒为 944px，高度随网格行数与副标题行数变化
     */
    private static void assertFixture(Path path, BufferedImage image, int expectedHeight) throws Exception {
        assertTrue(ImageIO.write(image, "png", path.toFile()), "夹具 PNG 应写入: " + path);
        assertTrue(Files.exists(path), "夹具 PNG 应存在: " + path);
        assertTrue(Files.size(path) > 0, "夹具 PNG 不应为空: " + path);
        assertEquals(944, image.getWidth(), "图片宽度恒为 944px: " + path);
        assertEquals(expectedHeight, image.getHeight(), "图片高度应随网格行数与副标题行数变化: " + path);
    }

    /**
     * 按口径创建夹具网格：单日只关心一天，其余口径共用典型周起始日
     */
    private static ActivityGridLayout gridOf(ActivityCaliberEnum caliber) {
        LocalDate anchor = caliber == ActivityCaliberEnum.SINGLE_DAY ? SINGLE_DAY_ANCHOR : FIXTURE_ANCHOR;
        return ActivityGridLayout.of(caliber, anchor);
    }

    /**
     * 夹具副标题：与真实服务一致地以口径与窗口起止开头
     */
    private static String fixtureSubtitle(ActivityCaliberEnum caliber, String metricPart) {
        LocalDate start = caliber == ActivityCaliberEnum.SINGLE_DAY ? SINGLE_DAY_ANCHOR : FIXTURE_ANCHOR;
        LocalDate end = start.plusDays(caliber.windowDays() - 1L);
        return "口径：" + caliber.keyword() + "（" + caliber.cellSpanLabel() + "）｜"
                + start + " ~ " + end + "｜" + metricPart;
    }

    /**
     * 个人夹具：典型周与半月首行比例递增并伴随不同暗化、第二行隔列有数据；
     * 单日一格只覆盖一个 15 分钟槽，格值只能是 0% 或 100%，并留出无数据格供人工区分
     */
    private static PersonalActivityHeatmapVO buildPersonalFixture(ActivityCaliberEnum caliber) {
        ActivityGridLayout grid = gridOf(caliber);
        PersonalActivityHeatmapVO vo = PersonalActivityHeatmapVO.empty("测试用户 [54321] 活跃度热力图", grid);
        vo.setSubtitle(fixtureSubtitle(caliber, "有效采样覆盖率: 62%"));
        vo.setHasData(true);
        vo.setTotalDays(caliber.windowDays());
        double[] rates = {0.05, 0.15, 0.3, 0.45, 0.55, 0.65, 0.75, 0.85, 0.95, 1.0, 0.5, 0.2,
                0.4, 0.6, 0.7, 0.8, 0.35, 0.25, 0.1, 0.9, 0.5, 0.15, 0.65, 0.4};
        boolean singleDay = caliber == ActivityCaliberEnum.SINGLE_DAY;
        for (int col = 0; col < grid.cols(); col++) {
            if (singleDay) {
                fillSingleDayColumn(vo, grid, col);
                continue;
            }
            vo.getObservedSamples()[0][col] = 4;
            vo.getActiveRate()[0][col] = rates[col % rates.length];
            vo.getIdleRatio()[0][col] = col / 23.0;
            if (col % 2 == 0) {
                vo.getObservedSamples()[1][col] = 4;
                vo.getActiveRate()[1][col] = rates[(col + 5) % rates.length];
                vo.getIdleRatio()[1][col] = 0.3;
            }
        }
        return vo;
    }

    /**
     * 填充单日个人图的一列：间隔留空表达无数据格，其余格为 0% 或 100%
     */
    private static void fillSingleDayColumn(PersonalActivityHeatmapVO vo, ActivityGridLayout grid, int col) {
        for (int row = 0; row < grid.rows(); row++) {
            if ((row + col) % 3 == 0) {
                continue;
            }
            boolean active = (row + col) % 2 == 0;
            vo.getObservedSamples()[row][col] = 1;
            vo.getActiveRate()[row][col] = active ? 1.0 : 0.0;
            vo.getIdleRatio()[row][col] = active ? 0.0 : 0.5;
        }
    }

    /**
     * 帮派渐变/暗色夹具：一行覆盖 0~100+ 锚点渐变区间（含 12.5/37.5 插值点）与 0~1 连续暗化，含无数据格
     *
     * @param caliber 统计口径
     * @param notice  副标题第二行提示，null 表示无提示
     */
    private static FactionActivityHeatmapVO buildFactionFixture(ActivityCaliberEnum caliber, String notice) {
        ActivityGridLayout grid = gridOf(caliber);
        FactionActivityHeatmapVO vo = FactionActivityHeatmapVO.empty("测试帮派 [20465] 活跃度热力图", grid);
        vo.setSubtitle(fixtureSubtitle(caliber, "格内：平均有效活跃人数"
                + "｜颜色：有效活跃人数渐变，Idle 越多越暗｜有效采样覆盖率: 71%"));
        vo.setNoticeMessage(notice);
        vo.setHasData(true);
        vo.setTotalDays(caliber.windowDays());
        double[] averages = {0, 12.5, 25, 37.5, 50, 66, 75, 88, 100, 130, 45, 55,
                30, 70, 95, 110, 20, 60, 80, 120, 5, 35, 85, 105};
        for (int col = 0; col < grid.cols(); col++) {
            vo.getObservedSamples()[0][col] = 4;
            vo.getAverageOnlineCount()[0][col] = averages[col % averages.length];
            vo.getIdleRatio()[0][col] = col / 23.0;
            if (col % 3 == 0) {
                vo.getObservedSamples()[1][col] = 4;
                vo.getAverageOnlineCount()[1][col] = averages[(col + 7) % averages.length];
                vo.getIdleRatio()[1][col] = 0.5;
            }
        }
        return vo;
    }
}
