package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票隔离回放边界测试,验证身份隔离和研究产物边界。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.29
 */
@DisplayName("股票隔离回放边界测试")
class StockReplayBoundaryTest {

    @Test
    @DisplayName("创建回放边界_相同组合每次运行拥有不同runId")
    void create_samePortfolio_generatesDifferentRunIds() {
        StockReplayBoundary first = StockReplayBoundary.create("VIP_FORMAL");
        StockReplayBoundary second = StockReplayBoundary.create("VIP_FORMAL");

        assertEquals("VIP_FORMAL", first.portfolioId());
        assertNotEquals(first.runId(), second.runId());
    }

    @Test
    @DisplayName("回放边界_显式定义四类研究产物")
    void boundary_definesRequiredResearchArtifacts() {
        Set<String> artifacts = new HashSet<>(StockReplayBoundary.RESEARCH_ARTIFACTS);

        assertTrue(artifacts.contains("summary.json"));
        assertTrue(artifacts.contains("trades.csv"));
        assertTrue(artifacts.contains("rejections.csv"));
        assertTrue(artifacts.contains("equity-curve.csv"));
    }

    @Test
    @DisplayName("回放边界_组合标识和运行标识不能为空")
    void boundary_blankIdentity_rejectsInput() {
        assertThrows(IllegalArgumentException.class,
                () -> new StockReplayBoundary(" ", "run-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new StockReplayBoundary("VIP_FORMAL", " "));
    }
}
