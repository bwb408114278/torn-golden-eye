package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回放轨道隔离和决策测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放轨道隔离测试")
class StockReplayTrackEnumIsolationTest {

    @Test
    @DisplayName("正式轨道五个槽位已占用_第六个候选记录无可用槽位")
    void formalTrack_fullSlots_recordsNoAvailableSlot() {
        StockReplayPortfolioEngine engine = new StockReplayPortfolioEngine();
        StockReplayPortfolioState state = StockReplayPortfolioState.initial(StockReplayTrackEnum.FORMAL_5_SLOT);
        for (int slotNo = 1; slotNo <= 5; slotNo++) {
            state = engine.enter(state, slotNo, 1000 + slotNo,
                    new BigDecimal("100.00"), new BigDecimal("2000000000.00")).state();
        }

        StockReplayDecisionEngine decisionEngine = decisionEngine(engine);
        StockReplayDecisionEngine.Decision decision = decisionEngine.allocateFormal(
                state, 1006, new BigDecimal("100.00"), LocalDateTime.of(2026, 1, 1, 10, 0));

        assertEquals("NO_AVAILABLE_SLOT", decision.reason());
        assertTrue(decision.state().slots().stream().allMatch(slot -> slot.stocksId() != null));
    }

    @Test
    @DisplayName("正式轨道占满_无限资金影子仍可独立建立理论持仓")
    void formalFull_doesNotBlockShadowTrack() {
        StockReplayPortfolioEngine engine = new StockReplayPortfolioEngine();
        StockReplayPortfolioState formal = StockReplayPortfolioState.initial(StockReplayTrackEnum.FORMAL_5_SLOT);
        for (int slotNo = 1; slotNo <= 5; slotNo++) {
            formal = engine.enter(formal, slotNo, 1000 + slotNo,
                    new BigDecimal("100.00"), new BigDecimal("2000000000.00")).state();
        }
        StockReplayPortfolioState shadow = StockReplayPortfolioState.initial(StockReplayTrackEnum.UNLIMITED_SHADOW);

        StockReplayDecisionEngine decisionEngine = decisionEngine(engine);
        StockReplayDecisionEngine.Decision decision = decisionEngine.allocateFormal(
                formal, 1006, new BigDecimal("100.00"), LocalDateTime.of(2026, 1, 1, 10, 0));
        StockReplayPortfolioEngine.EntryResult shadowEntry = engine.enter(
                new StockReplayPortfolioState(StockReplayTrackEnum.UNLIMITED_SHADOW,
                        List.of(StockReplaySlotState.available(1, new BigDecimal("1000000.00")))),
                1, 1006, new BigDecimal("100.00"), new BigDecimal("1000000.00"));

        assertEquals("NO_AVAILABLE_SLOT", decision.reason());
        assertEquals(1006, shadowEntry.state().slots().getFirst().stocksId());
        assertEquals(0, shadow.slots().size());
    }

    @Test
    @DisplayName("回放请求_四条轨道可以独立创建")
    void request_allTracks_createsIndependentStates() {
        StockReplayRequest request = new StockReplayRequest("VIP_FORMAL",
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 2, 0, 0),
                "BAR_V1", "FEATURE_V1", "BUY_V1", "SELL_V1", "ALLOC_V1", "MSG_V1",
                Path.of("target/replay"), EnumSet.allOf(StockReplayTrackEnum.class));

        StockReplayContext context = StockReplayContext.create(request);

        assertEquals(4, request.tracks().size());
        assertEquals(5, context.portfolioState(StockReplayTrackEnum.FORMAL_5_SLOT).slots().size());
        assertEquals(0, context.portfolioState(StockReplayTrackEnum.DYNAMIC_SELL_SHADOW).slots().size());
    }

    private StockReplayDecisionEngine decisionEngine(StockReplayPortfolioEngine engine) {
        return new StockReplayDecisionEngine(engine, List.of(),
                new pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService());
    }
}
