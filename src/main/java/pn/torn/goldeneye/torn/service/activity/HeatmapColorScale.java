package pn.torn.goldeneye.torn.service.activity;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.awt.*;

/**
 * 热力图固定 RGB 色板与暗化规则
 * <p>
 * 所有颜色映射、Idle 连续暗化和文字颜色选择的唯一来源；渲染器不得内嵌 RGB、暗化系数或人数档位。
 * 个人图使用 8 锚点 Viridis 风格连续渐变并支持按 idleRatio 暗化；帮派单图使用固定 5 档人数主色
 * 加 Idle 占比连续暗化；对比图使用 9 锚点蓝-灰-紫发散渐变。直接使用设计方案的固定 RGB 值，
 * 禁止在实施时重新选色，无数据格不进入渐变函数。
 *
 * @author Bai
 * @version 1.5.0
 * @since 2026.07.21
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HeatmapColorScale {

    // ==================== 通用界面颜色 ====================

    /**
     * 图片背景
     */
    public static final Color BG_COLOR = new Color(30, 30, 30);
    /**
     * 无数据格
     */
    public static final Color EMPTY_COLOR = new Color(45, 45, 45);
    /**
     * 网格线
     */
    public static final Color GRID_COLOR = new Color(60, 60, 60);
    /**
     * 主文字
     */
    public static final Color TEXT_COLOR = new Color(220, 220, 220);
    /**
     * 次文字
     */
    public static final Color SUB_TEXT_COLOR = new Color(160, 160, 160);
    /**
     * 无数据符号
     */
    public static final Color NO_DATA_SYMBOL_COLOR = new Color(138, 138, 138);
    /**
     * 深色背景文字
     */
    public static final Color DARK_BG_TEXT_COLOR = Color.WHITE;
    /**
     * 浅色背景文字
     */
    public static final Color LIGHT_BG_TEXT_COLOR = new Color(16, 16, 16);

    /**
     * 文字亮度阈值
     */
    static final double TEXT_LUMINANCE_THRESHOLD = 150;

    // ==================== 个人图与帮派图：8 锚点 Viridis 风格连续渐变 ====================

    /**
     * 比例锚点
     */
    static final double[] ACTIVITY_ANCHORS = {
            0.000, 0.143, 0.286, 0.429, 0.571, 0.714, 0.857, 1.000
    };

    /**
     * 个人活跃比例和帮派在线比例共用渐变色板
     */
    static final Color[] ACTIVITY_GRADIENT = {
            new Color(68, 1, 84),
            new Color(70, 50, 126),
            new Color(54, 92, 141),
            new Color(39, 127, 142),
            new Color(31, 161, 135),
            new Color(74, 193, 109),
            new Color(160, 218, 57),
            new Color(253, 231, 37)
    };

    // ==================== 帮派对比：9 锚点蓝-灰-紫连续渐变 ====================

    /**
     * 归一化锚点
     */
    static final double[] COMPARISON_ANCHORS = {
            -1.00, -0.75, -0.50, -0.25, 0.00, 0.25, 0.50, 0.75, 1.00
    };

    /**
     * 帮派对比渐变色板（B蓝 -> 灰 -> A紫）
     */
    static final Color[] COMPARISON_GRADIENT = {
            new Color(33, 102, 172),
            new Color(67, 147, 195),
            new Color(146, 197, 222),
            new Color(209, 229, 240),
            new Color(242, 242, 242),
            new Color(225, 213, 234),
            new Color(194, 165, 207),
            new Color(153, 112, 171),
            new Color(118, 42, 131)
    };

    /**
     * 对比持平色
     */
    public static final Color COMPARISON_NEUTRAL_COLOR = new Color(242, 242, 242);

    // ==================== 帮派图：固定 5 档人数主色 + Idle 连续暗化 ====================

    /**
     * 帮派图人数档位步长（每档 25 人）
     */
    static final int FACTION_TIER_STEP = 25;

    /**
     * 帮派图人数档位数量（0/25/50/75/100+）
     */
    static final int FACTION_TIER_COUNT = 5;

    /**
     * Idle 占比 100% 时的最大暗化系数（主色通道 × (1 - 0.45)）
     */
    static final double IDLE_MAX_DARKEN_FACTOR = 0.45;

    /**
     * 帮派图固定 5 档主色（Viridis 强对比锚点，按平均有效活跃人数选档）
     */
    static final Color[] FACTION_TIER_MAIN = {
            new Color(68, 1, 84),
            new Color(59, 82, 139),
            new Color(33, 145, 140),
            new Color(94, 201, 98),
            new Color(253, 231, 37)
    };

    /**
     * 帮派图图例标签（与 5 档主色一一对应）
     */
    public static final String[] FACTION_TIER_LABELS = {"0", "25", "50", "75", "100+"};

    /**
     * 计算帮派图人数档位索引：{@code floor(averageActiveCount / 25)} 并 clamp 到 [0, 4]。
     * <p>
     * 档位始终只由平均有效活跃人数决定，不按成员数、比例、查询区间或用户配置自适应调整。
     *
     * @param averageActiveCount 平均有效活跃人数 A（A &gt;= 0）
     * @return 档位索引 [0, 4]
     */
    public static int factionTierIndex(double averageActiveCount) {
        double clamped = Math.clamp(averageActiveCount, 0, Double.MAX_VALUE);
        return (int) Math.min(FACTION_TIER_COUNT - 1, Math.floor(clamped / FACTION_TIER_STEP));
    }

    /**
     * 帮派图单元格颜色：按平均有效活跃人数选 5 档主色，再按 idleRatio 连续暗化。
     *
     * @param averageActiveCount 平均有效活跃人数 A（决定档位，不受 I 影响）
     * @param idleRatio          idle 占比 I/(A+I)，值域 [0,1]，会被 clamp
     * @return 渲染色
     */
    public static Color factionColor(double averageActiveCount, double idleRatio) {
        Color mainColor = FACTION_TIER_MAIN[factionTierIndex(averageActiveCount)];
        return darken(mainColor, idleRatio);
    }

    /**
     * 个人图单元格颜色：连续比例色板基础上按 idleRatio 连续暗化。
     *
     * @param ratio     有效活跃比例 [0,1]
     * @param idleRatio idle 占比 [0,1]，会被 clamp
     * @return 渲染色
     */
    public static Color darkenedActivityColor(double ratio, double idleRatio) {
        return darken(activityColor(ratio), idleRatio);
    }

    /**
     * 按 idle 占比对主色做连续暗化：
     * {@code brightnessMultiplier = 1 - 0.45 × idleRatio}，
     * {@code renderColor = round(mainColorChannel × brightnessMultiplier)}。
     * <p>
     * idleRatio = 0 返回完整主色；idleRatio = 1 返回主色各通道 × 0.55 四舍五入的最大暗化色。
     *
     * @param color     主色
     * @param idleRatio idle 占比 [0,1]，会被 clamp
     * @return 暗化后的颜色
     */
    public static Color darken(Color color, double idleRatio) {
        double brightnessMultiplier = 1 - IDLE_MAX_DARKEN_FACTOR * Math.clamp(idleRatio, 0, 1);
        int r = (int) Math.round(color.getRed() * brightnessMultiplier);
        int g = (int) Math.round(color.getGreen() * brightnessMultiplier);
        int b = (int) Math.round(color.getBlue() * brightnessMultiplier);
        return new Color(r, g, b);
    }

    // ==================== 渐变映射方法 ====================

    /**
     * 将 [0,1] 比例值映射为 ACTIVITY_GRADIENT 渐变色
     *
     * @param ratio 比例值，会被 clamp 到 [0,1]
     * @return 渐变色
     */
    public static Color activityColor(double ratio) {
        return interpolateLinear(Math.clamp(ratio, 0, 1), ACTIVITY_ANCHORS, ACTIVITY_GRADIENT);
    }

    /**
     * 将 [-1,1] 归一化值映射为 COMPARISON_GRADIENT 渐变色
     *
     * @param normalized 归一化值，会被 clamp 到 [-1,1]
     * @return 渐变色
     */
    public static Color comparisonColor(double normalized) {
        return interpolateLinear(Math.clamp(normalized, -1, 1), COMPARISON_ANCHORS, COMPARISON_GRADIENT);
    }

    /**
     * 对比图 P95 归一化
     *
     * @param diff  原始差值
     * @param scale P95(abs(所有共同有效格子的 diff))，必须为正数
     * @return 归一化值 [-1,1]
     */
    public static double normalizeComparisonDiff(double diff, double scale) {
        double minSafety = 0.5;
        return Math.clamp(diff / Math.max(scale, minSafety), -1, 1);
    }

    // ==================== 文字颜色 ====================

    /**
     * 根据背景色亮度选择文字颜色
     *
     * @param bgColor 背景色
     * @return 深色背景返回白色，浅色背景返回深色
     */
    public static Color textColorFor(Color bgColor) {
        return isDarkColor(bgColor) ? DARK_BG_TEXT_COLOR : LIGHT_BG_TEXT_COLOR;
    }

    /**
     * 判断颜色是否为深色（用于选择文字颜色）
     *
     * @param c 颜色
     * @return true 表示深色背景，应使用白色文字
     */
    static boolean isDarkColor(Color c) {
        return c.getRed() * 0.299 + c.getGreen() * 0.587 + c.getBlue() * 0.114 < TEXT_LUMINANCE_THRESHOLD;
    }

    // ==================== 内部工具方法 ====================

    /**
     * 在锚点数组之间做线性插值
     *
     * @param value   输入值
     * @param anchors 锚点位置数组
     * @param colors  锚点颜色数组
     * @return 插值后的颜色
     */
    static Color interpolateLinear(double value, double[] anchors, Color[] colors) {
        if (value <= anchors[0]) {
            return colors[0];
        }
        if (value >= anchors[anchors.length - 1]) {
            return colors[colors.length - 1];
        }
        for (int i = 0; i < anchors.length - 1; i++) {
            if (value >= anchors[i] && value <= anchors[i + 1]) {
                double t = (value - anchors[i]) / (anchors[i + 1] - anchors[i]);
                return lerpColor(colors[i], colors[i + 1], t);
            }
        }
        return colors[colors.length - 1];
    }

    /**
     * 对两个颜色做线性插值
     *
     * @param c1 起始色
     * @param c2 结束色
     * @param t  插值因子 [0,1]
     * @return 插值色
     */
    static Color lerpColor(Color c1, Color c2, double t) {
        int r = (int) Math.round(c1.getRed() + (c2.getRed() - c1.getRed()) * t);
        int g = (int) Math.round(c1.getGreen() + (c2.getGreen() - c1.getGreen()) * t);
        int b = (int) Math.round(c1.getBlue() + (c2.getBlue() - c1.getBlue()) * t);
        return new Color(r, g, b);
    }

}
