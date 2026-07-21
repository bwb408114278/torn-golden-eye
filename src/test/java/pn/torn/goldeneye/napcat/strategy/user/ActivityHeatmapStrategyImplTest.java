package pn.torn.goldeneye.napcat.strategy.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活跃度热力图指令参数校验测试。
 *
 * @author Bai
 * @version 1.2.11
 * @since 2026.07.21
 */
@DisplayName("活跃度热力图指令参数校验测试")
class ActivityHeatmapStrategyImplTest {

    @Test
    @DisplayName("合法类型和正数ID应通过校验")
    void shouldAcceptValidTypeAndPositiveId() {
        assertTrue(ActivityHeatmapStrategyImpl.isValidQuery("帮派", "20465"));
        assertTrue(ActivityHeatmapStrategyImpl.isValidQuery("用户", "12345"));
    }

    @Test
    @DisplayName("无效类型及非正数ID应拒绝")
    void shouldRejectInvalidTypeAndNonPositiveId() {
        assertFalse(ActivityHeatmapStrategyImpl.isValidQuery("其他", "20465"));
        assertFalse(ActivityHeatmapStrategyImpl.isValidQuery("帮派", "0"));
        assertFalse(ActivityHeatmapStrategyImpl.isValidQuery("用户", "-1"));
        assertFalse(ActivityHeatmapStrategyImpl.isValidQuery(
                "用户", "999999999999999999999999999"));
    }
}
