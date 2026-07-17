package pn.torn.goldeneye.torn.service.faction.oc.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcPlanMode;
import pn.torn.goldeneye.torn.model.faction.crime.planning.OcRefreshInstructionPlan;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OC刷新指令渲染器测试。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("OC刷新指令渲染")
class OcNewTeamPlanRendererTest {
    private final OcNewTeamPlanRenderer renderer = new OcNewTeamPlanRenderer();

    @Test
    @DisplayName("应仅渲染非零的计划OC和刷新次数")
    void shouldRenderOnlyNonZeroPlannedOcAndRefreshCounts() {
        OcRefreshInstructionPlan plan = new OcRefreshInstructionPlan(20465L,
                LocalDateTime.of(2026, 7, 16, 15, 4), OcPlanMode.BALANCED,
                Map.of("8:Clinical Precision", 1, "8:Stacking the Deck", 0),
                2, 1, false, "安全边界验证通过", List.of());

        String text = renderer.render(plan);

        assertTrue(text.contains("8级 Clinical Precision: 1个"));
        assertFalse(text.contains("Stacking the Deck"));
        assertTrue(text.contains("普通池: 刷新2次"));
        assertTrue(text.contains("高阶池: 刷新1次"));
        assertFalse(text.contains("旧队补位"));
        assertFalse(text.contains("高阶链:"));
        assertFalse(text.contains("→"));
    }

    @Test
    @DisplayName("没有安全操作时应省略零次数池并提示暂不刷新")
    void shouldOmitZeroPoolAndRenderStopWhenNoSafeActionExists() {
        OcRefreshInstructionPlan plan = new OcRefreshInstructionPlan(20465L,
                LocalDateTime.of(2026, 7, 16, 15, 4), OcPlanMode.CONSERVATIVE,
                Map.of(), 0, 0, false, "当前无法证明安全刷新", List.of());

        String text = renderer.render(plan);

        assertTrue(text.contains("暂不刷新"));
        assertFalse(text.contains("普通池: 刷新"));
        assertFalse(text.contains("高阶池: 刷新"));
        assertFalse(text.contains("当前计划OC"));
    }
}
