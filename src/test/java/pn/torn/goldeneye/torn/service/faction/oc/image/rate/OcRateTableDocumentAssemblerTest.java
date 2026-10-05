package pn.torn.goldeneye.torn.service.faction.oc.image.rate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.image.document.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户OC成功率表文档组装测试：行结构、单元格顺序、样式枚举、星级行省略与固定文案。
 *
 * @author Bai
 * @version 1.9.0
 * @since 2026.10.05
 */
@DisplayName("用户OC成功率表文档组装测试")
class OcRateTableDocumentAssemblerTest {
    private static final int EXPECTED_COLUMN_COUNT = 7;
    private final OcRateTableDocumentAssembler assembler = new OcRateTableDocumentAssembler();
    private TableDocument document;

    @BeforeEach
    void setUp() {
        TornUserDO user = new TornUserDO();
        user.setId(1L);
        user.setNickname("示例玩家");
        user.setFactionId(2095L);

        List<TornSettingOcSlotDO> allSlotList = new ArrayList<>();
        allSlotList.addAll(craneReactionSlots());
        allSlotList.addAll(goneFissionSlots());
        allSlotList.addAll(breakTheBankSlots());
        allSlotList.addAll(stackingTheDeckSlots());

        List<TornFactionOcUserDO> ocUserList = new ArrayList<>();
        ocUserList.addAll(craneReactionRecords());
        ocUserList.addAll(goneFissionRecords());
        ocUserList.addAll(breakTheBankRecords());
        ocUserList.addAll(stackingTheDeckRecords());

        List<TornSettingOcDO> ocList = List.of(
                oc("Crane Reaction", 10),
                oc("Gone Fission", 9),
                oc("Break the Bank", 8),
                oc("Stacking the Deck", 8),
                oc("Window of Opportunity", 7));

        OcRateTableData data = new OcRateTableData(user, ocList, allSlotList, ocUserList, List.of());
        document = assembler.assemble(data);
    }

    @Test
    @DisplayName("文档外壳使用OC成功率主题、固定宽度与标题跨全表")
    void assemble_documentShell() {
        assertEquals(TableThemeEnum.OC_RATE.getDocumentType(), document.documentType());
        assertEquals(1600, document.width());
        assertEquals("示例玩家的OC成功率", document.title());
        assertEquals(6, document.rows().size());

        TableCell titleCell = document.rows().getFirst().cells().getFirst();
        assertEquals(TableCellStyleEnum.TITLE, titleCell.style());
        assertEquals(EXPECTED_COLUMN_COUNT, titleCell.colSpan());
        assertEquals("示例玩家的OC成功率", titleCell.text());
    }

    @Test
    @DisplayName("每OC一行，岗位按编码排序，数值格按五档着色")
    void assemble_ocRowTierStyles() {
        List<TableCell> cells = document.rows().get(3).cells();
        assertEquals(EXPECTED_COLUMN_COUNT, cells.size());

        TableCell ocCell = cells.getFirst();
        assertEquals(TableCellStyleEnum.OC_GROUP_CORE, ocCell.style());
        assertEquals(List.of("8级·核心", "Break the Bank"), lineTexts(ocCell));
        assertEquals(List.of(TableCellContent.LineEmphasis.SUB, TableCellContent.LineEmphasis.MAIN),
                lineEmphases(ocCell));

        assertEquals(List.of("Hacker#1", "⚔️★★★★★ 💰★★", "无记录", "要求55"), lineTexts(cells.get(1)));
        assertEquals(TableCellStyleEnum.RATE_NONE, cells.get(1).style());

        assertEquals(List.of("Muscle#1", "⚔️★★★★ 💰★★★★", "78", "要求65"), lineTexts(cells.get(2)));
        assertEquals(TableCellStyleEnum.RATE_EXCEED, cells.get(2).style());

        assertEquals(List.of("Muscle#2", "⚔️★", "63", "要求55"), lineTexts(cells.get(3)));
        assertEquals(TableCellStyleEnum.RATE_PASS, cells.get(3).style());

        assertEquals(List.of("Robber#1", "💰★★★", "51", "要求65"), lineTexts(cells.get(4)));
        assertEquals(TableCellStyleEnum.RATE_FAIL_FAR, cells.get(4).style());

        assertEquals(List.of("Thief#1", "58", "要求60"), lineTexts(cells.get(5)));
        assertEquals(TableCellStyleEnum.RATE_FAIL_NEAR, cells.get(5).style());

        assertEquals(List.of("Thief#2", "⚔️★★★★★ 💰★★★★★", "80", "要求65"), lineTexts(cells.get(6)));
        assertEquals(TableCellStyleEnum.RATE_EXCEED, cells.get(6).style());
    }

    @Test
    @DisplayName("链式前置OC只展示成功率星级，收益星级被省略，9级前置同样生效")
    void assemble_chainPrerequisiteHidesFortuneStars() {
        List<TableCell> goneFissionCells = document.rows().get(2).cells();
        assertEquals(TableCellStyleEnum.OC_GROUP_CHAIN, goneFissionCells.getFirst().style());
        assertEquals(List.of("9级·连锁前置", "Gone Fission"), lineTexts(goneFissionCells.getFirst()));
        assertEquals(List.of("Hacker#1", "⚔️★★★★★", "80", "要求65"), lineTexts(goneFissionCells.get(1)));
        assertEquals(TableCellStyleEnum.RATE_EXCEED, goneFissionCells.get(1).style());

        List<TableCell> cells = document.rows().get(4).cells();

        assertEquals(TableCellStyleEnum.OC_GROUP_CHAIN, cells.getFirst().style());
        assertEquals(List.of("8级·连锁前置", "Stacking the Deck"), lineTexts(cells.getFirst()));
        assertEquals(List.of("Cat Burglar#1", "⚔️★★★★", "72", "要求60"), lineTexts(cells.get(1)));
        assertEquals(List.of("Driver#1", "48", "要求55"), lineTexts(cells.get(2)));
        assertEquals(TableCellStyleEnum.RATE_FAIL_NEAR, cells.get(2).style());
    }

    @Test
    @DisplayName("岗位不足最大列数时以空白格补齐，尾部补齐格无内容")
    void assemble_padsTailCells() {
        List<TableCell> craneCells = document.rows().get(1).cells();
        assertEquals(EXPECTED_COLUMN_COUNT, craneCells.size());
        assertEquals(List.of("10级·连锁", "Crane Reaction"), lineTexts(craneCells.getFirst()));
        assertEquals(List.of("Bomber#1", "⚔️★★★ 💰★★★★", "61", "要求60"), lineTexts(craneCells.get(1)));
        assertEquals(List.of("Engineer#1", "⚔️★★★★ 💰★★★★★", "无记录", "要求60"), lineTexts(craneCells.get(2)));
        assertEquals(List.of("Lookout#1", "⚔️★★★★ 💰★★★★", "68", "要求60"), lineTexts(craneCells.get(3)));
        assertEquals(List.of("Sniper#1", "⚔️★★★★★ 💰★★★★★", "76", "要求70"), lineTexts(craneCells.get(4)));

        assertEquals(TableCellStyleEnum.SLOT_EMPTY, craneCells.get(5).style());
        assertEquals(TableCellStyleEnum.SLOT_EMPTY, craneCells.get(6).style());
        assertInstanceOf(TableCellContent.PlainText.class, craneCells.get(5).content());
        assertEquals("", craneCells.get(5).text());
    }

    @Test
    @DisplayName("页脚为固定三行图例，无成功率记录的OC整行跳过")
    void assemble_footerLegendAndSkippedOc() {
        TableCell footerCell = document.rows().get(5).cells().getFirst();
        assertEquals(TableCellStyleEnum.FOOTER, footerCell.style());
        assertEquals(EXPECTED_COLUMN_COUNT, footerCell.colSpan());
        assertEquals(TableTextOverflowEnum.WRAP, footerCell.overflow());
        String expectedFooter = """
                色阶：超出要求≥10 ｜ 达到要求 ｜ 低于要求·差距<10 ｜ 低于要求·差距≥10 ｜ 无记录
                级别分组：入门（7级及以下） ｜ 核心（8级） ｜ 连锁前置（各级别）与连锁（9~10级）\u3000\u3000⚔️ 影响成功率 ｜ 💰 影响大成功收益（各1~5级，未配置不显示；连锁前置岗位仅展示⚔️）
                要求＝目标成员所在帮派的岗位要求（各帮派不同）""";
        assertEquals(expectedFooter, footerCell.text());

        boolean containsWindowOfOpportunity = document.rows().stream()
                .flatMap(row -> row.cells().stream())
                .anyMatch(cell -> cell.text().contains("Window of Opportunity"));
        assertFalse(containsWindowOfOpportunity);
    }

    private List<String> lineTexts(TableCell cell) {
        return stackedText(cell).lines().stream().map(TableCellContent.Line::text).toList();
    }

    private List<TableCellContent.LineEmphasis> lineEmphases(TableCell cell) {
        return stackedText(cell).lines().stream().map(TableCellContent.Line::emphasis).toList();
    }

    private TableCellContent.StackedText stackedText(TableCell cell) {
        return assertInstanceOf(TableCellContent.StackedText.class, cell.content());
    }

    private List<TornSettingOcSlotDO> breakTheBankSlots() {
        return List.of(
                slot("Break the Bank", 8, "Hacker#1", 55, 28, BigDecimal.valueOf(12)),
                slot("Break the Bank", 8, "Muscle#1", 65, 22, BigDecimal.valueOf(30)),
                slot("Break the Bank", 8, "Muscle#2", 55, 5, BigDecimal.ZERO),
                slot("Break the Bank", 8, "Robber#1", 65, 0, BigDecimal.valueOf(22)),
                slot("Break the Bank", 8, "Thief#1", 60, 0, null),
                slot("Break the Bank", 8, "Thief#2", 65, 30, BigDecimal.valueOf(40)));
    }

    private List<TornFactionOcUserDO> breakTheBankRecords() {
        return List.of(
                userRecord("Break the Bank", 8, "Muscle#1", 78),
                userRecord("Break the Bank", 8, "Muscle#2", 63),
                userRecord("Break the Bank", 8, "Robber#1", 51),
                userRecord("Break the Bank", 8, "Thief#1", 58),
                userRecord("Break the Bank", 8, "Thief#2", 80));
    }

    private List<TornSettingOcSlotDO> craneReactionSlots() {
        return List.of(
                slot("Crane Reaction", 10, "Bomber#1", 60, 15, BigDecimal.valueOf(25)),
                slot("Crane Reaction", 10, "Engineer#1", 60, 20, BigDecimal.valueOf(35)),
                slot("Crane Reaction", 10, "Lookout#1", 60, 20, BigDecimal.valueOf(30)),
                slot("Crane Reaction", 10, "Sniper#1", 70, 25, BigDecimal.valueOf(40)));
    }

    private List<TornFactionOcUserDO> craneReactionRecords() {
        return List.of(
                userRecord("Crane Reaction", 10, "Bomber#1", 61),
                userRecord("Crane Reaction", 10, "Lookout#1", 68),
                userRecord("Crane Reaction", 10, "Sniper#1", 76));
    }

    private List<TornSettingOcSlotDO> goneFissionSlots() {
        return List.of(
                slot("Gone Fission", 9, "Hacker#1", 65, 25, BigDecimal.valueOf(38.93)),
                slot("Gone Fission", 9, "Muscle#1", 60, 17, BigDecimal.valueOf(10.50)));
    }

    private List<TornFactionOcUserDO> goneFissionRecords() {
        return List.of(
                userRecord("Gone Fission", 9, "Hacker#1", 80));
    }

    private List<TornSettingOcSlotDO> stackingTheDeckSlots() {
        return List.of(
                slot("Stacking the Deck", 8, "Cat Burglar#1", 60, 24, BigDecimal.valueOf(39)),
                slot("Stacking the Deck", 8, "Driver#1", 55, 0, BigDecimal.valueOf(20)));
    }

    private List<TornFactionOcUserDO> stackingTheDeckRecords() {
        return List.of(
                userRecord("Stacking the Deck", 8, "Cat Burglar#1", 72),
                userRecord("Stacking the Deck", 8, "Driver#1", 48));
    }

    private TornSettingOcDO oc(String ocName, int rank) {
        TornSettingOcDO oc = new TornSettingOcDO();
        oc.setOcName(ocName);
        oc.setRank(rank);
        return oc;
    }

    private TornSettingOcSlotDO slot(String ocName, int rank, String slotCode, int passRate,
                                     Integer priority, BigDecimal bestSuccess) {
        TornSettingOcSlotDO slot = new TornSettingOcSlotDO();
        slot.setOcName(ocName);
        slot.setRank(rank);
        slot.setSlotCode(slotCode);
        slot.setSlotShortCode(slotCode);
        slot.setPassRate(passRate);
        slot.setPriority(priority);
        slot.setBestSuccess(bestSuccess);
        return slot;
    }

    private TornFactionOcUserDO userRecord(String ocName, int rank, String position, int passRate) {
        TornFactionOcUserDO userRecord = new TornFactionOcUserDO();
        userRecord.setOcName(ocName);
        userRecord.setRank(rank);
        userRecord.setPosition(position);
        userRecord.setPassRate(passRate);
        return userRecord;
    }
}
