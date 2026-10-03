package pn.torn.goldeneye.torn.service.activity.render;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.awt.*;

/**
 * 活跃度热力图绘制样式常量
 * <p>
 * 字体、提示色、图例刻度与格内符号被标题、网格区、图例区三个绘制器共用；集中一处后各绘制器
 * 不再各留一份字面量。只承载"长什么样"，不承载布局坐标、口径语义或颜色算法
 * （颜色一律来自{@link HeatmapColorScale}）。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.10.03
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class HeatmapRenderStyle {
    /**
     * 图片字体
     */
    static final String IMAGE_FONT = "Microsoft YaHei";
    /**
     * 标题字体
     */
    static final Font TITLE_FONT = new Font(IMAGE_FONT, Font.BOLD, 15);
    /**
     * 副标题字体
     */
    static final Font SUBTITLE_FONT = new Font(IMAGE_FONT, Font.PLAIN, 11);
    /**
     * 时间轴表头字体
     */
    static final Font HEADER_FONT = new Font(IMAGE_FONT, Font.BOLD, 12);
    /**
     * 行标签与图例标签字体
     */
    static final Font LABEL_FONT = new Font(IMAGE_FONT, Font.PLAIN, 11);
    /**
     * 格内文字字体
     */
    static final Font CELL_FONT = new Font(IMAGE_FONT, Font.BOLD, 10);
    /**
     * 数据不完整/legacy 提示文字颜色（橙色）
     */
    static final Color NOTICE_COLOR = new Color(255, 152, 0);
    /**
     * 个人图图例刻度
     */
    static final int[] LEGEND_TICKS = {0, 25, 50, 75, 100};
    /**
     * 无数据符号
     */
    static final String NO_DATA_SYMBOL = "-";
    /**
     * 百分号
     */
    static final String PERCENT = "%";
}
