package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcDO;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.image.document.TableCellStyleEnum;
import pn.torn.goldeneye.utils.image.document.TableDocument;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 当前OC表格文档组装测试，聚焦结构、推荐语义和唯一成员Emoji。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@DisplayName("OC表格文档组装测试")
class OcTableDocumentAssemblerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 12, 0);

    @Test
    @DisplayName("普通OC块包含标题、跨列分隔、团队状态、岗位、成员和页脚")
    void assemble_shouldBuildCurrentOcDocument() {
        OcTableDocumentAssembler assembler = new OcTableDocumentAssembler(
                new OcImageStatusResolver(), new OcImageTitleFormatter());
        TornFactionOcDO oc = buildOc();
        TornFactionOcSlotDO filled = buildSlot(10L, "Kidnap", BigDecimal.ZERO);
        TornFactionOcSlotDO empty = buildSlot(null, "Muscle", BigDecimal.ZERO);
        TornUserDO user = new TornUserDO();
        user.setId(10L);
        user.setNickname("成员");

        TableDocument document = assembler.assemble("标题", List.of(oc),
                Map.of(oc.getId(), List.of(empty, filled)), Map.of(10L, user), "页脚", NOW);

        assertEquals(5, document.rows().size());
        assertEquals(3, document.rows().get(0).cells().get(0).colSpan());
        assertEquals(TableCellStyleEnum.SECTION, document.rows().get(1).cells().get(0).style());
        assertEquals(3, document.rows().get(1).cells().get(0).colSpan());
        assertEquals(TableCellStyleEnum.TEAM_READY, document.rows().get(2).cells().get(0).style());
        assertEquals(TableCellStyleEnum.SLOT_FILLED, document.rows().get(2).cells().get(1).style());
        assertEquals(TableCellStyleEnum.SLOT_IDLE, document.rows().get(2).cells().get(2).style());
        assertEquals("成员[10] 💤", document.rows().get(3).cells().get(0).text());
        assertEquals("空缺", document.rows().get(3).cells().get(1).text());
        assertEquals(TableCellStyleEnum.FOOTER, document.rows().get(4).cells().get(0).style());
    }

    @Test
    @DisplayName("推荐块只将对应空槽标记为推荐，其他空槽为空转样式")
    void assemble_shouldApplyRecommendationOnlyToEmptyRecommendedSlot() {
        OcTableDocumentAssembler assembler = new OcTableDocumentAssembler(
                new OcImageStatusResolver(), new OcImageTitleFormatter());
        TornFactionOcDO oc = buildOc();
        TornFactionOcSlotDO recommended = buildSlot(null, "Kidnap", BigDecimal.ZERO);
        TornFactionOcSlotDO idle = buildSlot(null, "Muscle", BigDecimal.ZERO);

        TableDocument document = assembler.assemble("推荐", List.of(new OcTableDocumentAssembler.Block(
                oc, List.of(idle, recommended), "理由", "Kidnap")), Map.of(), null, NOW);

        assertEquals(TableCellStyleEnum.SLOT_RECOMMENDED, document.rows().get(2).cells().get(1).style());
        assertEquals(TableCellStyleEnum.SLOT_IDLE, document.rows().get(2).cells().get(2).style());
        assertEquals("空缺", document.rows().get(3).cells().get(0).text());
        assertEquals("空缺", document.rows().get(3).cells().get(1).text());
    }

    private TornFactionOcDO buildOc() {
        TornFactionOcDO oc = new TornFactionOcDO();
        oc.setId(1L);
        oc.setName("Clinical Precision");
        oc.setStatus("Recruiting");
        oc.setReadyTime(NOW.plusHours(1));
        return oc;
    }

    private TornFactionOcSlotDO buildSlot(Long userId, String position, BigDecimal progress) {
        TornFactionOcSlotDO slot = new TornFactionOcSlotDO();
        slot.setOcId(1L);
        slot.setUserId(userId);
        slot.setPosition(position);
        slot.setProgress(progress);
        return slot;
    }
}
