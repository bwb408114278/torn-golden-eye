package pn.torn.goldeneye.utils.image.render.html;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.utils.image.document.TableCell;
import pn.torn.goldeneye.utils.image.document.TableCellStyleEnum;
import pn.torn.goldeneye.utils.image.document.TableDocument;
import pn.torn.goldeneye.utils.image.document.TableRow;
import pn.torn.goldeneye.utils.image.document.TableTextOverflowEnum;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定HTML结构、转义和枚举样式映射测试。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@DisplayName("HTML表格标记渲染测试")
class HtmlTableMarkupRendererTest {
    private final HtmlTableMarkupRenderer renderer = new HtmlTableMarkupRenderer();

    @Test
    @DisplayName("动态文本应转义，span和样式只能输出受控结果")
    void shouldEscapeTextAndRenderControlledAttributes() {
        TableDocument document = new TableDocument("标题 <危险>", List.of(new TableRow(List.of(
                new TableCell("<>&\"' 中文", TableCellStyleEnum.SLOT_RECOMMENDED, 2, 3,
                        TableTextOverflowEnum.ELLIPSIS)))), 1600, "test");

        String html = renderer.render(document);

        assertTrue(html.contains("标题 &lt;危险&gt;"));
        assertTrue(html.contains("&lt;&gt;&amp;&quot;&#39; 中文"));
        assertTrue(html.contains("class=\"cell-slot-recommended overflow-ellipsis\""));
        assertTrue(html.contains("rowspan=\"2\""));
        assertTrue(html.contains("colspan=\"3\""));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("href="));
        assertFalse(html.contains("style="));
    }

    @Test
    @DisplayName("所有语义样式和溢出策略应映射为固定class")
    void shouldRenderFixedClassNames() {
        List<TableCell> cells = List.of(
                new TableCell("标题", TableCellStyleEnum.TITLE, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("分组", TableCellStyleEnum.SECTION, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("完成", TableCellStyleEnum.TEAM_READY, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("警告", TableCellStyleEnum.TEAM_WARNING, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("岗位", TableCellStyleEnum.SLOT_FILLED, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("空位", TableCellStyleEnum.SLOT_EMPTY, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("推荐", TableCellStyleEnum.SLOT_RECOMMENDED, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("空转", TableCellStyleEnum.SLOT_IDLE, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("成员", TableCellStyleEnum.MEMBER_FILLED, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("空成员", TableCellStyleEnum.MEMBER_EMPTY, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("页脚", TableCellStyleEnum.FOOTER, 1, 1, TableTextOverflowEnum.CLIP)
        );
        String html = renderer.render(new TableDocument("测试", List.of(new TableRow(cells)), 1600, "test"));

        assertTrue(html.contains("cell-title"));
        assertTrue(html.contains("cell-section"));
        assertTrue(html.contains("cell-team-ready"));
        assertTrue(html.contains("cell-team-warning"));
        assertTrue(html.contains("cell-slot-filled"));
        assertTrue(html.contains("cell-slot-empty"));
        assertTrue(html.contains("cell-slot-recommended"));
        assertTrue(html.contains("cell-slot-idle"));
        assertTrue(html.contains("cell-member-filled"));
        assertTrue(html.contains("cell-member-empty"));
        assertTrue(html.contains("cell-footer overflow-clip"));
    }
}
