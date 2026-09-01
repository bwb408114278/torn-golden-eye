package pn.torn.goldeneye.torn.service.faction.oc.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.faction.oc.TornFactionOcUserDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcDO;
import pn.torn.goldeneye.repository.model.setting.TornSettingOcSlotDO;
import pn.torn.goldeneye.repository.model.user.TornUserDO;
import pn.torn.goldeneye.utils.image.document.TableCell;
import pn.torn.goldeneye.utils.image.document.TableDocument;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 历史和候选OC表格文档组装测试。
 *
 * @author Bai
 * @version 1.6.0
 * @since 2026.08.31
 */
@DisplayName("OC历史表格文档组装测试")
class OcHistoryTableDocumentAssemblerTest {
    private final OcHistoryTableDocumentAssembler assembler = new OcHistoryTableDocumentAssembler();

    @Test
    @DisplayName("候选成员文档保持六列且不携带当前OC状态字段")
    void buildFreeMemberDocument_shouldBuildSixColumns() {
        TornFactionOcUserDO candidate = new TornFactionOcUserDO();
        candidate.setUserId(100L);
        candidate.setOcName("Test OC");
        candidate.setPosition("Thief#1");
        candidate.setPassRate(80);
        TornUserDO user = new TornUserDO();
        user.setNickname("测试用户");

        TableDocument document = assembler.buildFreeMemberDocument(List.of(candidate), Map.of(100L, user));

        assertEquals("可加入OC成员", document.title());
        assertEquals(3, document.rows().size());
        assertEquals(6, document.rows().get(1).cells().size());
        assertEquals("测试用户", document.rows().get(2).cells().get(2).text());
        assertFalse(documentText(document).contains("readyTime"));
        assertFalse(documentText(document).contains("💤"));
    }

    @Test
    @DisplayName("成功率文档按岗位列展示历史成功率并保留合并关系")
    void buildPassRateDocument_shouldBuildPositionAndRateRows() {
        TornUserDO user = new TornUserDO();
        user.setNickname("测试用户");
        TornSettingOcDO oc = new TornSettingOcDO();
        oc.setOcName("Test OC");
        oc.setRank(8);
        TornSettingOcSlotDO slot = new TornSettingOcSlotDO();
        slot.setOcName("Test OC");
        slot.setSlotCode("A");
        slot.setSlotShortCode("Thief#1");
        TornFactionOcUserDO history = new TornFactionOcUserDO();
        history.setOcName("Test OC");
        history.setPosition("Thief#1");
        history.setPassRate(75);

        TableDocument document = assembler.buildPassRateDocument(user, List.of(oc), List.of(slot), List.of(history));

        assertEquals("测试用户的OC成功率", document.title());
        assertEquals(4, document.rows().size());
        TableCell section = document.rows().get(1).cells().getFirst();
        assertEquals(2, section.colSpan());
        assertEquals("75", document.rows().get(3).cells().get(1).text());
        assertTrue(documentText(document).contains("Test OC"));
        assertFalse(documentText(document).contains("⚠️"));
    }

    private String documentText(TableDocument document) {
        return document.rows().stream()
                .flatMap(row -> row.cells().stream())
                .map(TableCell::text)
                .reduce("", String::concat);
    }
}
