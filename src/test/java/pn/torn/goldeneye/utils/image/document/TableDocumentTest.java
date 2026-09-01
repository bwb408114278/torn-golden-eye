package pn.torn.goldeneye.utils.image.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 表格文档不可变性和构造约束测试。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@DisplayName("表格文档模型测试")
class TableDocumentTest {
    @Test
    @DisplayName("文档和行集合应防御性复制")
    void shouldDefensivelyCopyRows() {
        List<TableRow> rows = new ArrayList<>(List.of(new TableRow(List.of(
                new TableCell("中文", TableCellStyleEnum.TITLE, 1, 1, TableTextOverflowEnum.WRAP)))));
        TableDocument document = new TableDocument("标题", rows, 1600, "test");

        rows.clear();

        assertEquals(1, document.rows().size());
        List<TableRow> documentRows = document.rows();
        List<TableCell> documentCells = documentRows.getFirst().cells();
        assertThrows(UnsupportedOperationException.class, documentRows::clear);
        assertThrows(UnsupportedOperationException.class, documentCells::clear);
    }

    @Test
    @DisplayName("非法标题、空行集合和跨行列应快速失败")
    void shouldRejectInvalidDocumentValues() {
        TableRow row = new TableRow(List.of(
                new TableCell("内容", TableCellStyleEnum.SECTION, 1, 1, TableTextOverflowEnum.CLIP)));
        List<TableRow> emptyRows = List.of();
        List<TableRow> validRows = List.of(row);

        assertThrows(IllegalArgumentException.class, () -> new TableDocument(" ", validRows, 100, "test"));
        assertThrows(IllegalArgumentException.class, () -> new TableDocument("标题", emptyRows, 100, "test"));
        assertThrows(IllegalArgumentException.class,
                () -> new TableCell("内容", TableCellStyleEnum.SECTION, 0, 1, TableTextOverflowEnum.CLIP));
        assertThrows(IllegalArgumentException.class,
                () -> new TableCell("内容", TableCellStyleEnum.SECTION, 1, 0, TableTextOverflowEnum.CLIP));
    }
}
