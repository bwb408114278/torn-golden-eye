package pn.torn.goldeneye.torn.service.activity;

import pn.torn.goldeneye.torn.model.activity.BaseActivityHeatmapVO;
import pn.torn.goldeneye.torn.model.activity.grid.ActivityGridLayout;

/**
 * 活跃度热力图动态布局
 * <p>
 * 口径网格决定行列数与网格宽度，副标题行数决定纵向位置；绘制方法只接收本 record，不再各自
 * 推导坐标。尺寸常量集中在此，渲染器与两个绘制器共用同一套几何事实。
 *
 * @param imageWidth  图片总宽度
 * @param gridWidth   网格区总宽度
 * @param imageHeight 图片总高度
 * @param timeAxisY   时间轴区顶部 Y
 * @param gridY       网格区顶部 Y
 * @param legendY     图例区顶部 Y
 * @param grid        口径网格，提供行列数与行列标签
 * @author Bai
 * @version 1.7.0
 * @since 2026.10.03
 */
record HeatmapLayout(
        int imageWidth,
        int gridWidth,
        int imageHeight,
        int timeAxisY,
        int gridY,
        int legendY,
        ActivityGridLayout grid) {

    /**
     * 外边距
     */
    static final int PADDING = 16;
    /**
     * 标题区高度
     */
    static final int TITLE_HEIGHT = 28;
    /**
     * 副标题单行高度
     */
    static final int SUBTITLE_HEIGHT = 20;
    /**
     * 时间轴区高度
     */
    static final int TIME_AXIS_HEIGHT = 24;
    /**
     * 单元格尺寸（正方形）
     */
    static final int CELL_SIZE = 36;
    /**
     * 行标签宽度
     */
    static final int ROW_LABEL_WIDTH = 48;
    /**
     * 图例区高度
     */
    static final int LEGEND_HEIGHT = 44;
    /**
     * 标题区顶部 Y
     */
    static final int TITLE_Y = PADDING;
    /**
     * 副标题区顶部 Y
     */
    static final int SUBTITLE_Y = TITLE_Y + TITLE_HEIGHT;
    /**
     * 网格区左侧 X（行标签宽度固定，与网格列数无关）
     */
    static final int GRID_X = PADDING + ROW_LABEL_WIDTH;

    /**
     * 按口径网格与副标题行数计算动态布局。
     *
     * @param vo         热力图数据
     * @param noticeLine 是否存在副标题第二行提示
     * @return 本次渲染使用的布局
     */
    static HeatmapLayout forVo(BaseActivityHeatmapVO vo, boolean noticeLine) {
        ActivityGridLayout grid = vo.getGrid();
        int timeAxisY = SUBTITLE_Y + (noticeLine ? 2 : 1) * SUBTITLE_HEIGHT;
        int gridY = timeAxisY + TIME_AXIS_HEIGHT;
        int gridWidth = grid.cols() * CELL_SIZE;
        int imageWidth = PADDING + ROW_LABEL_WIDTH + gridWidth + PADDING;
        int legendY = gridY + grid.rows() * CELL_SIZE;
        int imageHeight = legendY + LEGEND_HEIGHT + PADDING;
        return new HeatmapLayout(imageWidth, gridWidth, imageHeight, timeAxisY, gridY, legendY, grid);
    }
}
