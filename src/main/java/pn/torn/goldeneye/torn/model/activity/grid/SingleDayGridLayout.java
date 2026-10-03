package pn.torn.goldeneye.torn.model.activity.grid;

import java.time.LocalDate;

/**
 * 单日网格：4 行刻钟位 × 24 小时列。
 * <p>
 * 一天 96 个槽要落进 24 列的宽度里，所以列取绝对小时、行取该小时内的第几个刻钟，
 * 读图时“列 14 + 行 :15”即 14:15，不需要换算。
 *
 * @param date 查询锚点日，只用于文案，不参与落格
 * @author Bai
 * @version 1.7.0
 * @since 2026.10.03
 */
public record SingleDayGridLayout(LocalDate date) implements ActivityGridLayout {

    private static final int ROWS = 4;
    private static final int COLS = 24;
    private static final int SLOTS_PER_HOUR = 4;
    private static final String[] ROW_LABELS = {":00", ":15", ":30", ":45"};

    @Override
    public int rows() {
        return ROWS;
    }

    @Override
    public int cols() {
        return COLS;
    }

    @Override
    public String rowLabel(int row) {
        return ROW_LABELS[row];
    }

    @Override
    public String colLabel(int col) {
        return String.valueOf(col);
    }

    @Override
    public Position position(LocalDate slotDate, int slot) {
        return new Position(slot % SLOTS_PER_HOUR, slot / SLOTS_PER_HOUR);
    }
}
