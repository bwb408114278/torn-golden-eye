package pn.torn.goldeneye.torn.service.stocks.alert.shadow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockEligibilityService.EligibilityResult;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockEligibilityService;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.StockBuyStrategy;

/**
 * 股票影子记录写入器决策测试,覆盖资格通过与实际正式接纳事实分离。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.28
 */
@DisplayName("股票影子记录写入器决策测试")
class StockShadowRecordWriterDecisionTest {

    @Test
    @DisplayName("资格通过但正式批次为空_组合决策为SHADOW")
    void determinePortfolioDecision_allowedWithoutFormalBatch_returnsShadow() throws Exception {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(null, null);
        Method method = StockShadowTrackRecorder.class.getDeclaredMethod(
                "determinePortfolioDecision",
                StockShadowTrackRecorder.SignalEvaluationView.class,
                EligibilityResult.class,
                TornStockVirtualBatchDO.class);
        method.setAccessible(true);

        StockShadowTrackRecorder.SignalEvaluationView evaluation = new EvaluationView(true);
        EligibilityResult eligibility = new EligibilityResult(
                StockEligibilityResultEnum.ALLOWED, List.of());

        String decision = (String) method.invoke(recorder, evaluation, eligibility, null);

        assertEquals("SHADOW", decision);
    }

    @Test
    @DisplayName("资格通过且正式批次已保存_组合决策为FORMAL")
    void determinePortfolioDecision_allowedWithSavedFormalBatch_returnsFormal() throws Exception {
        StockShadowTrackRecorder recorder = new StockShadowTrackRecorder(null, null);
        Method method = StockShadowTrackRecorder.class.getDeclaredMethod(
                "determinePortfolioDecision",
                StockShadowTrackRecorder.SignalEvaluationView.class,
                EligibilityResult.class,
                TornStockVirtualBatchDO.class);
        method.setAccessible(true);

        StockShadowTrackRecorder.SignalEvaluationView evaluation = new EvaluationView(true);
        EligibilityResult eligibility = new EligibilityResult(
                StockEligibilityResultEnum.ALLOWED, List.of());
        TornStockVirtualBatchDO formalBatch = new TornStockVirtualBatchDO();
        formalBatch.setId(100L);

        String decision = (String) method.invoke(recorder, evaluation, eligibility, formalBatch);

        assertEquals("FORMAL", decision);
    }

    private record EvaluationView(boolean acceptedFormal)
            implements StockShadowTrackRecorder.SignalEvaluationView {
        @Override
        public Integer stocksId() {
            return 1001;
        }

        @Override
        public String stocksShortname() {
            return "TST";
        }

        @Override
        public pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.StockBuyStrategy primaryStrategy() {
            return null;
        }

        @Override
        public java.util.List<pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.StockBuyStrategy> matchedStrategies() {
            return List.of();
        }

        @Override
        public java.math.BigDecimal qualityScore() {
            return null;
        }

        @Override
        public boolean edgeTriggered() {
            return true;
        }

        @Override
        public pn.torn.goldeneye.torn.service.stocks.alert.signal.BuyContext context() {
            return null;
        }

        @Override
        public pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO monthlyState() {
            return null;
        }

        @Override
        public EligibilityResult eligibilityResult() {
            return null;
        }

        @Override
        public boolean acceptedFormal() {
            return acceptedFormal;
        }
    }
}
