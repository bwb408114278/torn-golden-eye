package pn.torn.goldeneye.torn.model.activity.grid;

import pn.torn.goldeneye.torn.model.activity.ActivityCaliberEnum;

import java.time.LocalDate;

/**
 * 活跃度热力图网格布局。
 * <p>
 * 定义“采样槽 → (行, 列)”的映射契约与唯一的布局工厂：聚合器与渲染器只面向行列数、
 * 标签与落格坐标编程，不感知具体口径。实现族密封，映射只允许出现在这三个实现中；
 * 新增口径必须在{@link #of(ActivityCaliberEnum, LocalDate)}中显式给出布局，否则无法编译。
 * <p>
 * 四个口径共用“24 小时列 + 每格 15 分钟”的骨架，因此列数、列标签与列号换算只有这里一份实现，
 * 实现类只回答随口径变化的行数与落格行号。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.10.03
 */
public sealed interface ActivityGridLayout
        permits SingleDayGridLayout, DayStripGridLayout, WeekdayHourGridLayout {

    /**
     * 每小时的采样槽数：落格换算与列宽度都以小时为单位。
     */
    int SLOTS_PER_HOUR = 4;

    /**
     * X 轴固定 24 小时：四个口径的列数与列标签一致。
     */
    int HOURS_PER_DAY = 24;

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
     * 网格列数：四个口径都是 24 小时列，改动列数只需改这一处。
     *
     * @return 列数
     */
    default int cols() {
        return HOURS_PER_DAY;
    }

    /**
     * 行标签。
     *
     * @param row 行号
     * @return 该行的展示文案
     */
    String rowLabel(int row);

    /**
     * 列标签：四个口径都按小时编号，列文案需要别的写法时再覆写。
     *
     * @param col 列号
     * @return 该列的展示文案
     */
    default String colLabel(int col) {
        return String.valueOf(col);
    }

    /**
     * 采样槽序号 → 列号：列号就是第几个小时。
     *
     * @param slot 采样槽序号（0-95）
     * @return 列号
     */
    default int colOf(int slot) {
        return slot / SLOTS_PER_HOUR;
    }

    /**
     * 采样槽 → 行号：唯一随口径变化的部分。
     *
     * @param date 槽归属自然日
     * @param slot 采样槽序号（0-95）
     * @return 行号
     */
    int rowOf(LocalDate date, int slot);

    /**
     * 采样槽 → 格坐标；行列与{@link #rowOf(LocalDate, int)}、{@link #colOf(int)}共用同一映射。
     *
     * @param date 槽归属自然日
     * @param slot 采样槽序号（0-95）
     * @return 落格坐标
     */
    default Position position(LocalDate date, int slot) {
        return new Position(rowOf(date, slot), colOf(slot));
    }

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