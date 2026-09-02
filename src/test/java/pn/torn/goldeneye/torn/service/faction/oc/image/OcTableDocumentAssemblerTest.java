package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.image.document.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 当前OC表格文档组装测试，聚焦展示模式、空缺语义、三段岗位和图例页脚。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@DisplayName("OC表格文档组装测试")
class OcTableDocumentAssemblerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 12, 0);

    @Test
    @DisplayName("当前OC：副标题徽章、三段岗位、暖橙真实空缺、图例加更新时间页脚")
    void assemble_shouldBuildCurrentOcDocument() {
        OcTableDocumentAssembler assembler = new OcTableDocumentAssembler(
                new OcImageStatusResolver(), new OcImageTitleFormatter());
        TornFactionOcDO oc = buildOc();
        TornFactionOcSlotDO filled = buildSlot(10L, "Kidnap", BigDecimal.ZERO, 76);
        TornFactionOcSlotDO empty = buildSlot(null, "Muscle", BigDecimal.ZERO, 88);
        TornUserDO user = new TornUserDO();
        user.setId(10L);
        user.setNickname("成员");

        TableDocument document = assembler.assemble("标题", List.of(oc),
                Map.of(oc.getId(), List.of(empty, filled)), Map.of(10L, user), "上次更新时间: 11:00", NOW);

        assertEquals(5, document.rows().size());
        assertEquals(3, document.rows().getFirst().cells().getFirst().colSpan());
        assertEquals(new TableCellContent.BadgeText("Clinical Precision", "1小时00分后停转",
                TableCellBadgeToneEnum.WARNING), document.rows().get(1).cells().getFirst().content());
        assertEquals(3, document.rows().get(1).cells().getFirst().colSpan());
        assertEquals(TableCellStyleEnum.TEAM_READY, document.rows().get(2).cells().getFirst().style());
        assertEquals(TableTextOverflowEnum.CLIP, document.rows().get(2).cells().getFirst().overflow());
        assertEquals(new TableCellContent.ThreePartText("💤", "Kidnap", "76"),
                document.rows().get(2).cells().get(1).content());
        assertEquals(TableCellStyleEnum.SLOT_FILLED, document.rows().get(2).cells().get(1).style());
        assertEquals(new TableCellContent.ThreePartText("", "Muscle", ""),
                document.rows().get(2).cells().get(2).content());
        assertEquals(TableCellStyleEnum.CURRENT_SLOT_EMPTY, document.rows().get(2).cells().get(2).style());
        assertEquals("成员[10]", document.rows().get(3).cells().getFirst().text());
        assertEquals(TableTextOverflowEnum.ELLIPSIS, document.rows().get(3).cells().getFirst().overflow());
        assertEquals("空缺", document.rows().get(3).cells().get(1).text());
        assertEquals(TableCellStyleEnum.CURRENT_MEMBER_EMPTY, document.rows().get(3).cells().get(1).style());
        assertEquals(TableCellStyleEnum.FOOTER, document.rows().get(4).cells().getFirst().style());
        assertTrue(document.rows().get(4).cells().getFirst().text()
                .startsWith("状态说明：💤 空转 ｜ ⏳ 准备中 ｜ ✅ 准备完成 ｜ ⚠️ 缺少道具"));
        assertTrue(document.rows().get(4).cells().getFirst().text().endsWith("｜ 上次更新时间: 11:00"));
    }

    @Test
    @DisplayName("推荐块：目标岗位青绿、普通空缺中性灰、成员空缺柔性和图例页脚")
    void assemble_shouldApplyRecommendationSemantics() {
        OcTableDocumentAssembler assembler = new OcTableDocumentAssembler(
                new OcImageStatusResolver(), new OcImageTitleFormatter());
        TornFactionOcDO oc = buildOc();
        TornFactionOcSlotDO recommended = buildSlot(null, "Kidnap", BigDecimal.ZERO, 1);
        TornFactionOcSlotDO idle = buildSlot(null, "Muscle", BigDecimal.ZERO, 1);

        TableDocument document = assembler.assemble("推荐", List.of(new OcTableDocumentAssembler.Block(
                oc, List.of(idle, recommended), "理由", "Kidnap")), Map.of(), NOW);

        assertEquals(TableCellStyleEnum.SLOT_RECOMMENDED, document.rows().get(2).cells().get(1).style());
        assertEquals(TableCellStyleEnum.SLOT_IDLE, document.rows().get(2).cells().get(2).style());
        assertEquals("空缺", document.rows().get(3).cells().getFirst().text());
        assertEquals(TableCellStyleEnum.MEMBER_EMPTY, document.rows().get(3).cells().getFirst().style());
        assertEquals("状态说明：💤 空转 ｜ ⏳ 准备中 ｜ ✅ 准备完成 ｜ ⚠️ 缺少道具",
                document.rows().get(4).cells().getFirst().text());
    }

    private TornFactionOcDO buildOc() {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(1L);
        oc.setName("Clinical Precision");
        oc.setStatus("Recruiting");
        oc.setReadyTime(NOW.plusHours(1));
        return oc;
    }

    private TornFactionOcSlotDO buildSlot(Long userId, String position, BigDecimal progress, Integer passRate) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(1L);
        slot.setUserId(userId);
        slot.setPosition(position);
        slot.setProgress(progress);
        slot.setPassRate(passRate);
        return slot;
    }
}
