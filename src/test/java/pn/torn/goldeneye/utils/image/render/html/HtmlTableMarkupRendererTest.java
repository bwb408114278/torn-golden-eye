package pn.torn.goldeneye.utils.image.render.html;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.utils.image.document.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定HTML结构、转义和枚举样式映射测试。
 *
 * @author Bai
 * @version 1.9.0
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
                        TableTextOverflowEnum.ELLIPSIS)))), 1600, TableThemeEnum.OC.getDocumentType());

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
                new TableCell("当前空岗", TableCellStyleEnum.CURRENT_SLOT_EMPTY, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("当前空成员", TableCellStyleEnum.CURRENT_MEMBER_EMPTY, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("成员", TableCellStyleEnum.MEMBER_FILLED, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("空成员", TableCellStyleEnum.MEMBER_EMPTY, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("页脚", TableCellStyleEnum.FOOTER, 1, 1, TableTextOverflowEnum.CLIP),
                new TableCell("表头", TableCellStyleEnum.HEADER, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("数据", TableCellStyleEnum.BODY, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("第一", TableCellStyleEnum.RANK_FIRST, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("第二", TableCellStyleEnum.RANK_SECOND, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("第三", TableCellStyleEnum.RANK_THIRD, 1, 1, TableTextOverflowEnum.WRAP)
        );
        String html = renderer.render(new TableDocument("测试", List.of(new TableRow(cells)), 1600,
                TableThemeEnum.OC.getDocumentType()));

        assertTrue(html.contains("cell-title"));
        assertTrue(html.contains("cell-section"));
        assertTrue(html.contains("cell-team-ready"));
        assertTrue(html.contains("cell-team-warning"));
        assertTrue(html.contains("cell-slot-filled"));
        assertTrue(html.contains("cell-slot-empty"));
        assertTrue(html.contains("cell-slot-recommended"));
        assertTrue(html.contains("cell-slot-idle"));
        assertTrue(html.contains("cell-current-slot-empty"));
        assertTrue(html.contains("cell-current-member-empty"));
        assertTrue(html.contains("cell-member-filled"));
        assertTrue(html.contains("cell-member-empty"));
        assertTrue(html.contains("cell-footer overflow-clip"));
        assertTrue(html.contains("cell-header"));
        assertTrue(html.contains("cell-body"));
        assertTrue(html.contains("cell-rank-first"));
        assertTrue(html.contains("cell-rank-second"));
        assertTrue(html.contains("cell-rank-third"));
    }

    @Test
    @DisplayName("堆叠内容应逐行转义并输出固定span结构")
    void shouldRenderStackedTextLinesEscaped() {
        TableDocument document = new TableDocument("堆叠内容", List.of(new TableRow(List.of(
                new TableCell(new TableCellContent.StackedText(List.of(
                        new TableCellContent.Line("<甲>&\"'", TableCellContent.LineEmphasis.MAIN),
                        new TableCellContent.Line("⚔️★ 💰★★", TableCellContent.LineEmphasis.NOTE),
                        new TableCellContent.Line("要求60", TableCellContent.LineEmphasis.SUB))),
                        TableCellStyleEnum.RATE_EXCEED, 1, 1, TableTextOverflowEnum.ELLIPSIS)))),
                1204, TableThemeEnum.OC_RATE.getDocumentType());

        String html = renderer.render(document);

        assertTrue(html.contains("<span class=\"cell-stacked\">"
                + "<span class=\"stacked-line stacked-main\">&lt;甲&gt;&amp;&quot;&#39;</span>"
                + "<span class=\"stacked-line stacked-note\">⚔️★ 💰★★</span>"
                + "<span class=\"stacked-line stacked-sub\">要求60</span></span>"));
        assertTrue(html.contains("class=\"cell-rate-exceed overflow-ellipsis\""));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("href="));
        assertFalse(html.contains("style="));
    }

    @Test
    @DisplayName("OC成功率新增样式应映射为固定class")
    void shouldRenderOcRateClassNames() {
        List<TableCell> cells = List.of(
                new TableCell("超出", TableCellStyleEnum.RATE_EXCEED, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("达到", TableCellStyleEnum.RATE_PASS, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("近差", TableCellStyleEnum.RATE_FAIL_NEAR, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("远差", TableCellStyleEnum.RATE_FAIL_FAR, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("无记录", TableCellStyleEnum.RATE_NONE, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("入门", TableCellStyleEnum.OC_GROUP_ENTRY, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("核心", TableCellStyleEnum.OC_GROUP_CORE, 1, 1, TableTextOverflowEnum.WRAP),
                new TableCell("连锁", TableCellStyleEnum.OC_GROUP_CHAIN, 1, 1, TableTextOverflowEnum.WRAP));
        String html = renderer.render(new TableDocument("测试", List.of(new TableRow(cells)), 1204,
                TableThemeEnum.OC_RATE.getDocumentType()));

        assertTrue(html.contains("cell-rate-exceed"));
        assertTrue(html.contains("cell-rate-pass"));
        assertTrue(html.contains("cell-rate-fail-near"));
        assertTrue(html.contains("cell-rate-fail-far"));
        assertTrue(html.contains("cell-rate-none"));
        assertTrue(html.contains("cell-oc-entry"));
        assertTrue(html.contains("cell-oc-core"));
        assertTrue(html.contains("cell-oc-chain"));
        assertFalse(html.contains("style="));
    }

    @Test
    @DisplayName("未注册主题应快速失败且不静默回退")
    void shouldFailFastForUnregisteredTheme() {
        TableDocument document = new TableDocument("标题", List.of(new TableRow(List.of(
                new TableCell("单元", TableCellStyleEnum.BODY, 1, 1, TableTextOverflowEnum.WRAP)))),
                1600, "unregistered-theme");

        assertThrows(IllegalArgumentException.class, () -> renderer.render(document));
    }

    @Test
    @DisplayName("徽章和三段内容应输出固定span结构且每段独立转义")
    void shouldRenderControlledSpansForStructuredContent() {
        TableDocument document = new TableDocument("内容模型", List.of(new TableRow(List.of(
                TableCell.badgeText("临床精确 <1>", "23小时47分后停转 <2>",
                        TableCellBadgeToneEnum.WARNING, TableCellStyleEnum.SECTION, 1, 1,
                        TableTextOverflowEnum.WRAP),
                TableCell.badgeText("推荐 <1>", List.of(
                                new TableCellContent.Badge("评分 88.6 <2>", TableCellBadgeToneEnum.SUCCESS),
                                new TableCellContent.Badge("成功率达标 <3>", TableCellBadgeToneEnum.NEUTRAL)),
                        TableCellStyleEnum.SECTION, 1, 1, TableTextOverflowEnum.WRAP),
                TableCell.threePartText("⚠️ <4>", "Assassin#1 <5>", "76 <6>",
                        TableCellStyleEnum.SLOT_FILLED, 1, 1, TableTextOverflowEnum.CLIP)))),
                1600, TableThemeEnum.OC.getDocumentType());

        String html = renderer.render(document);

        assertTrue(html.contains("<span class=\"cell-section-head\"><span class=\"cell-section-name\">"
                + "临床精确 &lt;1&gt;</span>"));
        assertTrue(html.contains("<span class=\"cell-badge badge-warning\">23小时47分后停转 &lt;2&gt;</span>"));
        assertTrue(html.contains("推荐 &lt;1&gt;</span>"
                + "<span class=\"cell-badge badge-success\">评分 88.6 &lt;2&gt;</span>"
                + "<span class=\"cell-badge badge-neutral\">成功率达标 &lt;3&gt;</span></span>"));
        assertTrue(html.contains("<span class=\"slot-parts\"><span class=\"slot-part-leading\">⚠️ &lt;4&gt;</span>"));
        assertTrue(html.contains("<span class=\"slot-part-center\">Assassin#1 &lt;5&gt;</span>"));
        assertTrue(html.contains("<span class=\"slot-part-trailing\">76 &lt;6&gt;</span>"));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("href="));
        assertFalse(html.contains("style="));
    }
}
