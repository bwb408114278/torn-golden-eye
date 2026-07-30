package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockMaturityEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRiskLevelEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockStrategyFitEnum;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.DeepMeanReversionBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.RangeLowerBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StrictReboundConfirmBuyStrategy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票回放纯买入策略决策测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放纯买入策略决策测试")
class StockReplayDecisionEngineTest {

    @Test
    @DisplayName("策略满足且资格通过_返回正式候选")
    void evaluateBuy_strategyMatchesAndEligibilityAllowed_returnsCandidate() {
        StockReplayDecisionEngine engine = engine();

        StockReplayDecisionEngine.BuyDecision decision = engine.evaluateBuy(
                context(StockStrategyFitEnum.NARROW), null, false,
                LocalDateTime.of(2026, 1, 1, 10, 0));

        assertTrue(decision.accepted());
        assertEquals("DEEP_MEAN_REVERSION_BUY", decision.strategyCode());
        assertEquals("ACCEPTED", decision.reason());
    }

    @Test
    @DisplayName("策略不满足_返回明确拒绝原因")
    void evaluateBuy_strategyDoesNotMatch_returnsRejected() {
        StockReplayDecisionEngine engine = engine();

        StockReplayDecisionEngine.BuyDecision decision = engine.evaluateBuy(
                context(StockStrategyFitEnum.STRONG), null, false,
                LocalDateTime.of(2026, 1, 1, 10, 0));

        assertFalse(decision.accepted());
        assertEquals("NO_BUY_STRATEGY_MATCH", decision.reason());
    }

    @Test
    @DisplayName("已预留全部槽位_分配器返回无可用槽位")
    void allocateFormal_reservedSlotsAreNotReused() {
        StockReplayPortfolioEngine portfolioEngine = new StockReplayPortfolioEngine();
        StockReplayPortfolioState state = StockReplayPortfolioState.initial(StockReplayTrackEnum.FORMAL_5_SLOT);
        for (int slotNo = 1; slotNo <= StockReplayRequest.FORMAL_SLOT_COUNT; slotNo++) {
            state = portfolioEngine.reserve(state, slotNo);
        }
        StockReplayDecisionEngine engine = new StockReplayDecisionEngine(
                portfolioEngine,
                List.of(new DeepMeanReversionBuyStrategy(), new RangeLowerBuyStrategy(),
                        new StrictReboundConfirmBuyStrategy()),
                new StockEligibilityService());

        StockReplayDecisionEngine.Decision decision = engine.allocateFormal(state, 1001,
                new BigDecimal("100"), LocalDateTime.of(2026, 1, 1, 10, 0));

        assertEquals("NO_AVAILABLE_SLOT", decision.reason());
    }

    private StockReplayDecisionEngine engine() {
        return new StockReplayDecisionEngine(
                new StockReplayPortfolioEngine(),
                List.of(new DeepMeanReversionBuyStrategy(), new RangeLowerBuyStrategy(),
                        new StrictReboundConfirmBuyStrategy()),
                new StockEligibilityService());
    }

    private BuyContext context(StockStrategyFitEnum style) {
        return new BuyContext(
                1001, "TEST", new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("-3.5"),
                new BigDecimal("-1"), new BigDecimal("-1"), new BigDecimal("-0.01"),
                new BigDecimal("-0.01"), new BigDecimal("-0.005"), new BigDecimal("-0.03"),
                new BigDecimal("90"), new BigDecimal("110"), new BigDecimal("0.20"),
                new BigDecimal("0.05"), new BigDecimal("0.001"), new BigDecimal("0.09"),
                Boolean.TRUE, style, StockMaturityEnum.M4_MATURE, StockRiskLevelEnum.NONE);
    }
}
