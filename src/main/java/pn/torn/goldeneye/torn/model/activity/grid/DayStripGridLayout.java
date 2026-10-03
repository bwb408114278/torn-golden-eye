package pn.torn.goldeneye.torn.model.activity.grid;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * 日条带网格：N 行具体日期 × 24 小时列，单周与半月共用，只差天数。
 *
 * @param startDate 窗口起始日
 * @param days      窗口天数，等于行数
 * @author Bai
 * @version 1.7.0
 * @since 2026.10.03
 */
public record DayStripGridLayout(LocalDate startDate, int days) implements ActivityGridLayout {

    private static final int COLS = 24;
    private static final int SLOTS_PER_HOUR = 4;
    private static final DateTimeFormatter ROW_LABEL_FMT = DateTimeFormatter.ofPattern("MM-dd");

    @Override
    public int rows() {
        return days;
    }

    @Override
    public int cols() {
        return COLS;
    }

    @Override
    public String rowLabel(int row) {
        return startDate.plusDays(row).format(ROW_LABEL_FMT);
    }

    @Override
    public String colLabel(int col) {
        return String.valueOf(col);
    }

    @Override
    public Position position(LocalDate date, int slot) {
        long offset = ChronoUnit.DAYS.between(startDate, date);
        if (offset < 0 || offset >= days) {
            throw new IllegalArgumentException("日期不在查询窗口内，禁止静默错位: " + date);
        }
        return new Position((int) offset, slot / SLOTS_PER_HOUR);
    }
}
