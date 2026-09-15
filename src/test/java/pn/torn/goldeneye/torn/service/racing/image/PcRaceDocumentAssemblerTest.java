package pn.torn.goldeneye.torn.service.racing.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceParticipantVO;
import pn.torn.goldeneye.torn.model.racing.view.PcRaceResultBO;
import pn.torn.goldeneye.utils.image.document.TableCellStyleEnum;
import pn.torn.goldeneye.utils.image.document.TableDocument;
import pn.torn.goldeneye.utils.image.document.TableRow;
import pn.torn.goldeneye.utils.image.document.TableThemeEnum;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PC赛车表格文档组装测试。
 *
 * @author Bai
 * @version 1.6.3
 * @since 2026.09.15
 */
@DisplayName("PC赛车表格文档组装测试")
class PcRaceDocumentAssemblerTest {
    private static final long RACE_ID = 1000L;

    private final PcRaceDocumentAssembler assembler = new PcRaceDocumentAssembler();

    @Test
    @DisplayName("行结构为标题、8列表头、榜单与合计页脚")
    void assemble_shouldBuildEightColumnStructure() {
        PcRaceResultBO result = result(List.of(
                participant(2L, 1, 1, "04:52:16.00", false),
                participant(1L, 2, 2, "04:53:00.00", false),
                participant(3L, null, null, null, true)));

        TableDocument document = assembler.assemble(result);

        assertEquals(TableThemeEnum.PC_RACE.getDocumentType(), document.documentType());
        assertEquals(1600, document.width());
        assertEquals("每日PC大赛成绩-Docks (2026-01-04)", document.title());
        List<TableRow> rows = document.rows();
        assertEquals(6, rows.size());
        TableRow titleRow = rows.getFirst();
        assertEquals(1, titleRow.cells().size());
        assertEquals(TableCellStyleEnum.TITLE, titleRow.cells().getFirst().style());
        assertEquals(8, titleRow.cells().getFirst().colSpan());
        TableRow headerRow = rows.get(1);
        assertEquals(8, headerRow.cells().size());
        headerRow.cells().forEach(cell -> assertEquals(TableCellStyleEnum.HEADER, cell.style()));
        assertEquals("SMTH名次", headerRow.cells().getFirst().text());
        assertEquals("创了", headerRow.cells().get(6).text());
        assertEquals("全场名次", headerRow.cells().get(7).text());
        TableRow footerRow = rows.getLast();
        assertEquals(1, footerRow.cells().size());
        assertEquals(TableCellStyleEnum.FOOTER, footerRow.cells().getFirst().style());
        assertEquals(8, footerRow.cells().getFirst().colSpan());
        assertTrue(footerRow.cells().getFirst().text().contains("家族参赛人数：45"));
    }

    @Test
    @DisplayName("前三名按SMTH名次标记奖牌且整行使用名次样式")
    void assemble_shouldMarkTopThreeWithMedalAndRankStyle() {
        PcRaceResultBO result = result(List.of(
                participant(11L, 1, 1, "04:52:16.00", false),
                participant(12L, 2, 2, "04:53:00.00", false),
                participant(13L, 3, 3, "04:54:00.00", false),
                participant(14L, 4, 4, "04:55:00.00", false)));

        List<TableRow> rows = assembler.assemble(result).rows();

        assertEquals(TableCellStyleEnum.RANK_FIRST, rows.get(2).cells().getFirst().style());
        assertEquals(TableCellStyleEnum.RANK_SECOND, rows.get(3).cells().getFirst().style());
        assertEquals(TableCellStyleEnum.RANK_THIRD, rows.get(4).cells().getFirst().style());
        assertEquals(TableCellStyleEnum.BODY, rows.get(5).cells().getFirst().style());
        assertEquals("🥇 1", rows.get(2).cells().getFirst().text());
        assertEquals("🥈 2", rows.get(3).cells().getFirst().text());
        assertEquals("🥉 3", rows.get(4).cells().getFirst().text());
        assertEquals("4", rows.get(5).cells().getFirst().text());
    }

    @Test
    @DisplayName("撞车行置底且名次与用时列显示占位符")
    void assemble_shouldRenderCrashedRowAtBottomWithPlaceholder() {
        PcRaceResultBO result = result(List.of(
                participant(1L, 1, 1, "04:52:16.00", false),
                participant(2L, null, null, null, true)));

        List<TableRow> rows = assembler.assemble(result).rows();

        TableRow crashedRow = rows.get(3);
        assertEquals("—", crashedRow.cells().getFirst().text());
        assertEquals("撞车", crashedRow.cells().get(6).text());
        assertEquals("—", crashedRow.cells().get(7).text());
        assertEquals("—", crashedRow.cells().get(4).text());
        assertEquals("—", crashedRow.cells().get(5).text());
        assertEquals(TableCellStyleEnum.BODY, crashedRow.cells().getFirst().style());
        assertEquals(TableCellStyleEnum.RANK_FIRST, rows.get(2).cells().getFirst().style());
    }

    @Test
    @DisplayName("榜单为空时输出暂无成绩记录且不抛异常")
    void assemble_shouldRenderEmptyPlaceholder() {
        PcRaceResultBO result = result(List.of());

        List<TableRow> rows = assembler.assemble(result).rows();

        assertEquals(4, rows.size());
        assertEquals("暂无成绩记录", rows.get(2).cells().getFirst().text());
    }

    private PcRaceResultBO result(List<PcRaceParticipantVO> participantList) {
        return new PcRaceResultBO(RACE_ID, LocalDate.of(2026, 1, 4), "Docks", LocalDateTime.of(2026, 1, 5, 0, 30),
                LocalDateTime.of(2026, 1, 5, 8, 30), participantList, null, List.of(), 45, 62,
                new BigDecimal("72.58"), null, null);
    }

    private PcRaceParticipantVO participant(long userId, Integer smthRank, Integer position, String raceTimeText,
                                            boolean crashed) {
        return new PcRaceParticipantVO(userId, "选手" + userId, "PHN", smthRank, position, raceTimeText,
                null, crashed);
    }
}
