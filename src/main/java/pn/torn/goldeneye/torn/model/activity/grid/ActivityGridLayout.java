package pn.torn.goldeneye.torn.model.activity.grid;

import pn.torn.goldeneye.torn.model.activity.ActivityCaliberEnum;

import java.time.LocalDate;

/**
 * 活跃度热力图网格布局。
 * <p>
 * 定义“采样槽 → (行, 列)”的映射契约与唯一的布局工厂：聚合器与渲染器只面向行列数、
 * 标签与落格坐标编程，不感知具体口径。实现族密封，映射只允许出现在这三个实现中；
 * 新增口径必须在{@link #of(ActivityCaliberEnum, LocalDate)}中显式给出布局，否则无法编译。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.10.03
 */
public sealed interface ActivityGridLayout
        permits SingleDayGridLayout, DayStripGridLayout, WeekdayHourGridLayout {

    /**
     * 格坐标。
     *
     * @param row 行号，自上而下从 0 开始
     * @param col 列号，自左向右从 0 开始
     */
    record Position(
            int row,
            int col) {
    }

    /**
     * 网格行数。
     *
     * @return 行数
     */
    int rows();

    /**
     * 网格列数。
     *
     * @return 列数
     */
    int cols();

    /**
     * 行标签。
     *
     * @param row 行号
     * @return 该行的展示文案
     */
    String rowLabel(int row);

    /**
     * 列标签。
     *
     * @param col 列号
     * @return 该列的展示文案
     */
    String colLabel(int col);

    /**
     * 把一个采样槽映射到格。
     *
     * @param date 槽归属自然日
     * @param slot 采样槽序号（0-95）
     * @return 落格坐标
     */
    Position position(LocalDate date, int slot);

    /**
     * 按口径与窗口起始日创建网格。
     *
     * @param caliber   统计口径
     * @param startDate 窗口起始日
     * @return 该口径对应的网格布局
     */
    static ActivityGridLayout of(ActivityCaliberEnum caliber, LocalDate startDate) {
        return switch (caliber) {
            case SINGLE_DAY -> new SingleDayGridLayout(startDate);
            case SINGLE_WEEK, HALF_MONTH -> new DayStripGridLayout(startDate, caliber.windowDays());
            case TYPICAL_WEEK -> WeekdayHourGridLayout.INSTANCE;
        };
    }
}
