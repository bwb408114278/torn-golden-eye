package pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * α策略执行bar策略测试。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.09.05
 */
@DisplayName("α策略执行bar策略测试")
class StockAlphaExecutionBarPolicyTest {
    private static final LocalDateTime DECISION = LocalDateTime.of(2026, 9, 5, 0, 10);
    private static final LocalDateTime EXECUTION_BAR = LocalDateTime.of(2026, 9, 5, 0, 15);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 0, 31);

    @Test
    @DisplayName("执行桶映射_决策时间向前对齐到下一个整15分钟桶")
    void mapsToNextExactBucket() {
        assertEquals(LocalDateTime.of(2026, 9, 5, 0, 15), StockAlphaExecutionBarPolicy.expectedExecutionBarStart(DECISION));
        assertEquals(LocalDateTime.of(2026, 9, 5, 0, 30), StockAlphaExecutionBarPolicy.expectedExecutionBarStart(
                LocalDateTime.of(2026, 9, 5, 0, 27)));
        assertEquals(LocalDateTime.of(2026, 9, 5, 0, 45), StockAlphaExecutionBarPolicy.expectedExecutionBarStart(
                LocalDateTime.of(2026, 9, 5, 0, 31)));
    }

    @Test
    @DisplayName("执行桶校验_仅接受已持久化的整桶起点,非整桶与空值直接拒绝")
    void requiresExactPersistedExecutionBucket() {
        assertEquals(EXECUTION_BAR, StockAlphaExecutionBarPolicy.requireExecutionBar(EXECUTION_BAR));
        assertEquals(LocalDateTime.of(2026, 9, 5, 0, 0),
                StockAlphaExecutionBarPolicy.previousBucket(EXECUTION_BAR));
        LocalDateTime misalignedBar = LocalDateTime.of(2026, 9, 5, 0, 16);
        assertThrows(IllegalArgumentException.class,
                () -> StockAlphaExecutionBarPolicy.requireExecutionBar(misalignedBar));
        assertThrows(IllegalArgumentException.class, () -> StockAlphaExecutionBarPolicy.requireExecutionBar(null));
    }

    @Test
    @DisplayName("执行桶边界_过期边界和跨桶换仓均不可执行")
    void rejectsStaleBoundaryAndInconsistentRebalance() {
        var valid = new StockAlphaExecutionBarPolicy.ExecutionBar(EXECUTION_BAR,
                LocalDateTime.of(2026, 9, 5, 0, 30), true, new BigDecimal("10"));
        var later = new StockAlphaExecutionBarPolicy.ExecutionBar(LocalDateTime.of(2026, 9, 5, 0, 30),
                LocalDateTime.of(2026, 9, 5, 0, 45), true, new BigDecimal("10"));
        assertTrue(StockAlphaExecutionBarPolicy.isExecutable(EXECUTION_BAR, valid, NOW));
        assertFalse(StockAlphaExecutionBarPolicy.isExecutable(EXECUTION_BAR, valid,
                LocalDateTime.of(2026, 9, 5, 0, 29, 59, 999_999_999)));
        assertFalse(StockAlphaExecutionBarPolicy.isExecutable(LocalDateTime.of(2026, 9, 5, 0, 30), valid, NOW));
        assertFalse(StockAlphaExecutionBarPolicy.isAtomicRebalance(EXECUTION_BAR, valid, later, NOW));
    }
}
