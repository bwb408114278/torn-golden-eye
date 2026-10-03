package pn.torn.goldeneye.torn.model.activity.grid;

import java.time.LocalDate;

/**
 * 典型周网格：7 行星期 × 24 小时列。
 * <p>
 * 窗口内同一(星期, 小时)的槽累计进同一格，因此格值是窗口均值；{@link #INSTANCE} 同时是
 * 活跃度对比图沿用既有 7×24 视图的默认网格与行列常量来源，对比图因此不需要第二套常量。
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.10.03
 */
public record WeekdayHourGridLayout() implements ActivityGridLayout {

    /**
     * 无参单例。
     */
    public static final WeekdayHourGridLayout INSTANCE = new WeekdayHourGridLayout();

    private static final int ROWS = 7;
    private static final String[] ROW_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    @Override
    public int rows() {
        return ROWS;
    }

    @Override
    public String rowLabel(int row) {
        return ROW_LABELS[row];
    }

    @Override
    public int rowOf(LocalDate date, int slot) {
        return rowOf(date);
    }

    /**
     * 日期对应的行号（周一为 0）。
     *
     * @param date 日期
     * @return 行号
     */
    public int rowOf(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }
}