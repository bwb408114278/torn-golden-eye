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

    @Test
    @DisplayName("受控单元格内容应拒绝null和空白非法值")
    void shouldRejectInvalidTableCellContentValues() {
        assertThrows(NullPointerException.class, () -> new TableCellContent.PlainText(null));
        assertThrows(NullPointerException.class,
                () -> new TableCellContent.BadgeText(null, "已停转", TableCellBadgeToneEnum.DANGER));
        assertThrows(IllegalArgumentException.class,
                () -> new TableCellContent.BadgeText("临床精确", " ", TableCellBadgeToneEnum.INFO));
        assertThrows(NullPointerException.class,
                () -> new TableCellContent.ThreePartText("✅", "岗位", null));
        assertThrows(IllegalArgumentException.class,
                () -> new TableCellContent.ThreePartText("✅", "  ", "76"));
    }

    @Test
    @DisplayName("纯文本兼容构造应统一映射为PlainText并保持text访问器语义")
    void shouldMapStringConstructorToPlainText() {
        TableCell cell = new TableCell("中文", TableCellStyleEnum.TITLE, 1, 1, TableTextOverflowEnum.WRAP);

        assertEquals(new TableCellContent.PlainText("中文"), cell.content());
        assertEquals("中文", cell.text());
    }

    @Test
    @DisplayName("徽章和三段内容应组合出可读纯文本")
    void shouldComposeReadableTextForStructuredContent() {
        TableCell badge = TableCell.badgeText("临床精确", "23小时47分后停转",
                TableCellBadgeToneEnum.WARNING, TableCellStyleEnum.SECTION, 1, 2, TableTextOverflowEnum.WRAP);
        TableCell threePart = TableCell.threePartText("✅", "Assassin#1", "76",
                TableCellStyleEnum.SLOT_FILLED, 1, 1, TableTextOverflowEnum.CLIP);
        TableCell emptyParts = TableCell.threePartText("", "CatBurglar#1", "",
                TableCellStyleEnum.SLOT_FILLED, 1, 1, TableTextOverflowEnum.CLIP);

        assertEquals("临床精确 23小时47分后停转", badge.text());
        assertEquals("✅ Assassin#1 76", threePart.text());
        assertEquals("CatBurglar#1", emptyParts.text());
    }
}
