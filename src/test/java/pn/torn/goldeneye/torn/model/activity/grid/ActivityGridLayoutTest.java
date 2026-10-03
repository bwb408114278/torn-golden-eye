package pn.torn.goldeneye.torn.model.activity.grid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.activity.ActivityCaliberEnum;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 活跃度热力图网格落格映射测试
 *
 * @author Bai
 * @version 1.7.0
 * @since 2026.10.03
 */
@DisplayName("活跃度热力图网格落格映射测试")
class ActivityGridLayoutTest {

    /**
     * 2026-09-22 为周二，便于校验星期序号
     */
    private static final LocalDate START = LocalDate.of(2026, 9, 22);

    @Test
    @DisplayName("单日网格为 4×24，列取小时、行取该小时内的刻钟位")
    void singleDayGrid_mapsSlotToQuarterRow() {
        ActivityGridLayout grid = ActivityGridLayout.of(ActivityCaliberEnum.SINGLE_DAY, START);

        assertEquals(4, grid.rows());
        assertEquals(24, grid.cols());
        assertEquals(":00", grid.rowLabel(0));
        assertEquals(":45", grid.rowLabel(3));
        assertEquals("0", grid.colLabel(0));
        assertEquals("23", grid.colLabel(23));

        ActivityGridLayout.Position position = grid.position(START, 14 * 4 + 1);
        assertEquals(1, position.row(), "14:15 落在刻钟位 :15");
        assertEquals(14, position.col(), "14:15 落在第 14 列");
        assertEquals(":15", grid.rowLabel(position.row()));
    }

    @Test
    @DisplayName("单周网格 7 行具体日期，行号取日期距窗口起始日的天数")
    void dayStripGrid_mapsDateToRow() {
        ActivityGridLayout grid = ActivityGridLayout.of(ActivityCaliberEnum.SINGLE_WEEK, START);

        assertEquals(7, grid.rows());
        assertEquals(24, grid.cols());
        assertEquals("09-22", grid.rowLabel(0));
        assertEquals("09-28", grid.rowLabel(6));

        ActivityGridLayout.Position position = grid.position(LocalDate.of(2026, 9, 25), 8);
        assertEquals(3, position.row());
        assertEquals(2, position.col());
    }

    @Test
    @DisplayName("半月网格 15 行，窗口外日期拒绝落格")
    void halfMonthGrid_rejectsDateOutsideWindow() {
        ActivityGridLayout grid = ActivityGridLayout.of(ActivityCaliberEnum.HALF_MONTH, START);

        assertEquals(15, grid.rows());
        assertEquals(24, grid.cols());
        assertEquals("10-06", grid.rowLabel(14));
        assertThrows(IllegalArgumentException.class,
                () -> grid.position(LocalDate.of(2026, 9, 21), 0));
        assertThrows(IllegalArgumentException.class,
                () -> grid.position(LocalDate.of(2026, 10, 7), 0));
    }

    @Test
    @DisplayName("典型周网格 7×24，行号为周一 0 至周日 6")
    void weekdayHourGrid_mapsWeekdayToRow() {
        ActivityGridLayout grid = ActivityGridLayout.of(ActivityCaliberEnum.TYPICAL_WEEK, START);

        assertEquals(7, grid.rows());
        assertEquals(24, grid.cols());
        assertEquals("周一", grid.rowLabel(0));
        assertEquals("周日", grid.rowLabel(6));
        assertEquals(0, grid.position(LocalDate.of(2026, 9, 21), 3).row(), "周一为第 0 行");
        assertEquals(6, grid.position(LocalDate.of(2026, 9, 27), 3).row(), "周日为第 6 行");
        assertEquals(0, grid.position(LocalDate.of(2026, 9, 21), 3).col(), "第 3 槽属于 0 时");
    }
}
