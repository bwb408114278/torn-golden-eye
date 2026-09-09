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
    private static final LocalDateTime DECISION = LocalDateTime.of(2026, 9, 5, 9, 45);
    private static final LocalDateTime EXECUTION_BAR = LocalDateTime.of(2026, 9, 5, 10, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 10, 16);

    @Test
    @DisplayName("执行桶映射_决策时点映射到下一根严格连续整15分钟桶")
    void mapsToNextExactBucket() {
        assertEquals(EXECUTION_BAR, StockAlphaExecutionBarPolicy.expectedExecutionBarStart(DECISION),
                "决策桶09:45必须映射到执行桶10:00");
        assertEquals(LocalDateTime.of(2026, 9, 5, 10, 0), StockAlphaExecutionBarPolicy.expectedExecutionBarStart(
                LocalDateTime.of(2026, 9, 5, 9, 57)));
        assertEquals(LocalDateTime.of(2026, 9, 5, 10, 15), StockAlphaExecutionBarPolicy.expectedExecutionBarStart(
                LocalDateTime.of(2026, 9, 5, 10, 1)));
        assertEquals(DECISION, StockAlphaExecutionBarPolicy.previousBucket(EXECUTION_BAR),
                "执行桶的前一根桶必须等于决策桶");
    }

    @Test
    @DisplayName("执行桶校验_仅接受已持久化的整桶起点,非整桶与空值直接拒绝")
    void requiresExactPersistedExecutionBucket() {
        assertEquals(EXECUTION_BAR, StockAlphaExecutionBarPolicy.requireExecutionBar(EXECUTION_BAR));
        LocalDateTime misalignedBar = LocalDateTime.of(2026, 9, 5, 10, 1);
        assertThrows(IllegalArgumentException.class,
                () -> StockAlphaExecutionBarPolicy.requireExecutionBar(misalignedBar));
        assertThrows(IllegalArgumentException.class, () -> StockAlphaExecutionBarPolicy.requireExecutionBar(null));
    }

    @Test
    @DisplayName("执行桶边界_过期边界和跨桶换仓均不可执行")
    void rejectsStaleBoundaryAndInconsistentRebalance() {
        var valid = new StockAlphaExecutionBarPolicy.ExecutionBar(EXECUTION_BAR,
                LocalDateTime.of(2026, 9, 5, 10, 15), true, new BigDecimal("10"));
        var later = new StockAlphaExecutionBarPolicy.ExecutionBar(LocalDateTime.of(2026, 9, 5, 10, 15),
                LocalDateTime.of(2026, 9, 5, 10, 30), true, new BigDecimal("10"));
        assertTrue(StockAlphaExecutionBarPolicy.isExecutable(EXECUTION_BAR, valid, NOW));
        assertFalse(StockAlphaExecutionBarPolicy.isExecutable(EXECUTION_BAR, valid,
                LocalDateTime.of(2026, 9, 5, 10, 14, 59, 999_999_999)), "执行bar未结束时不得成交");
        assertFalse(StockAlphaExecutionBarPolicy.isExecutable(LocalDateTime.of(2026, 9, 5, 10, 15), valid, NOW),
                "行情bar与持久化执行桶不一致时不得成交");
        assertFalse(StockAlphaExecutionBarPolicy.isAtomicRebalance(EXECUTION_BAR, valid, later, NOW),
                "双腿不在同一执行桶时不得换仓");
    }
}
