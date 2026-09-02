package pn.torn.goldeneye.utils.image.document;

import java.util.List;
import java.util.Objects;

/**
 * 不可变表格行值对象。
 *
 * @param cells 行内单元格，不能为空且不能包含null
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
public record TableRow(
        List<TableCell> cells
) {

    /**
     * 创建并防御性复制表格行。
     */
    public TableRow {
        Objects.requireNonNull(cells, "cells不能为null");
        cells = List.copyOf(cells);
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("表格行不能为空");
        }
    }
}
