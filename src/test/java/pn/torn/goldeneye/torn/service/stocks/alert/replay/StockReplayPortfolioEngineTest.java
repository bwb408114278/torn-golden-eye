package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 回放5槽资金引擎测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放5槽资金引擎测试")
class StockReplayPortfolioEngineTest {

    @Test
    @DisplayName("入场_按整数股数扣除实际成本并保留余款")
    void enter_usesIntegerQuantityAndKeepsRemainingCash() {
        StockReplayPortfolioEngine engine = new StockReplayPortfolioEngine();
        StockReplayPortfolioState state = StockReplayPortfolioState.initial(StockReplayTrackEnum.FORMAL_5_SLOT);

        StockReplayPortfolioEngine.EntryResult result = engine.enter(state, 1,
                1001, new BigDecimal("3.00"), new BigDecimal("2000000000.00"));

        assertEquals(666666666L, result.quantity());
        assertEquals(0, result.state().slots().getFirst().availableCash()
                .compareTo(new BigDecimal("2.00")));
        assertEquals(1001, result.state().slots().getFirst().stocksId());
    }

    @Test
    @DisplayName("预留槽位_成交后仅扣除实际成本并清空预留资金")
    void reservedSlot_entryUsesReservedCashAndClearsReservation() {
        StockReplayPortfolioEngine engine = new StockReplayPortfolioEngine();
        StockReplayPortfolioState state = StockReplayPortfolioState.initial(StockReplayTrackEnum.FORMAL_5_SLOT);

        StockReplayPortfolioState reserved = engine.reserve(state, 1);
        StockReplayPortfolioEngine.EntryResult result = engine.enterReserved(
                reserved, 1, 1001, new BigDecimal("100.00"));

        assertEquals(0, result.state().slots().getFirst().reservedCash().signum());
        assertEquals(20000000L, result.quantity());
        assertEquals(0, result.state().slots().getFirst().availableCash().signum());
    }

    @Test
    @DisplayName("卖出_扣除百分之零点一费用并释放原槽位")
    void exit_appliesSellFeeAndReleasesSlot() {
        StockReplayPortfolioEngine engine = new StockReplayPortfolioEngine();
        StockReplayPortfolioState state = StockReplayPortfolioState.initial(StockReplayTrackEnum.FORMAL_5_SLOT);
        StockReplayPortfolioEngine.EntryResult entered = engine.enter(state, 1,
                1001, new BigDecimal("100.00"), new BigDecimal("2000000000.00"));

        StockReplayPortfolioState exited = engine.exit(entered.state(), 1, new BigDecimal("110.00"));

        assertEquals(0, exited.slots().getFirst().stocksId() == null ? 0 : 1);
        assertEquals(0, exited.slots().getFirst().availableCash()
                .compareTo(new BigDecimal("2197800000.00")));
    }

    @Test
    @DisplayName("权益_行情不足时不把成本伪装成持仓市值")
    void calculateEquity_missingPriceReturnsInsufficientStatus() {
        StockReplayPortfolioEngine engine = new StockReplayPortfolioEngine();
        StockReplayPortfolioState state = StockReplayPortfolioState.initial(StockReplayTrackEnum.FORMAL_5_SLOT);
        StockReplayPortfolioEngine.EntryResult entered = engine.enter(state, 1,
                1001, new BigDecimal("100.00"), new BigDecimal("2000000000.00"));

        StockReplayPortfolioEngine.EquityResult equity = engine.calculateEquity(entered.state(), List.of());

        assertEquals(StockReplayPortfolioEngine.DATA_INSUFFICIENT, equity.status());
        assertNull(equity.equity());
    }
}
