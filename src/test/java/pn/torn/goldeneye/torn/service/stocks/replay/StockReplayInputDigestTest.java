package pn.torn.goldeneye.torn.service.stocks.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 回放输入内容摘要领域测试。
 *
 * <p>保护R2-P1-1: 同一时间边界、行数与版本下,仅修改一行bar lastPrice/feature值/月度状态
 * 决策字段,内容摘要必须变化;相同输入摘要可复算一致。该摘要纳入sourceManifest hash,避免
 * "同边界不同内容"被误判为同一次成功结果。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
@DisplayName("回放输入内容摘要测试")
class StockReplayInputDigestTest {

    private static final int STOCK_ID = 1;
    private static final LocalDateTime T = LocalDateTime.of(2026, 8, 1, 10, 0);
    private static final LocalDate MONTH = LocalDate.of(2026, 8, 1);

    @Test
    @DisplayName("相同输入_内容摘要一致")
    void sameInput_sameDigest() {
        assertEquals(digestOf(buildWindow(100, 200, "NARROW")),
                digestOf(buildWindow(100, 200, "NARROW")), "相同输入摘要必须一致");
    }

    @Test
    @DisplayName("仅改bar lastPrice_行数边界不变_摘要变化")
    void changedBarPrice_digestChanges() {
        assertNotEquals(digestOf(buildWindow(100, 200, "NARROW")),
                digestOf(buildWindow(101, 200, "NARROW")), "bar价格变化摘要必须变化");
    }

    @Test
    @DisplayName("仅改feature值_行数边界不变_摘要变化")
    void changedFeatureValue_digestChanges() {
        assertNotEquals(digestOf(buildWindow(100, 200, "NARROW")),
                digestOf(buildWindow(100, 201, "NARROW")), "feature值变化摘要必须变化");
    }

    @Test
    @DisplayName("仅改月度状态决策字段_行数边界不变_摘要变化")
    void changedMonthlyStateDecision_digestChanges() {
        assertNotEquals(digestOf(buildWindow(100, 200, "NARROW")),
                digestOf(buildWindow(100, 200, "RANGING")), "月度风格变化摘要必须变化");
    }

    private static String digestOf(StockReplayInputDigestTest.Window window) {
        return StockReplayInputDigest.compute(window.bars(), window.features(), window.monthlyStates());
    }

    private static Window buildWindow(double barPrice, double featureReturn7d, String strategyFitPrior) {
        Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> bars = new HashMap<>();
        bars.put(STOCK_ID, new TreeMap<>());
        bars.get(STOCK_ID).put(T, bar(STOCK_ID, barPrice));

        Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> features = new HashMap<>();
        features.put(STOCK_ID, new TreeMap<>());
        features.get(STOCK_ID).put(T, feature(STOCK_ID, featureReturn7d));

        Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthly = new HashMap<>();
        monthly.put(MONTH, new HashMap<>());
        monthly.get(MONTH).put(STOCK_ID, monthlyState(strategyFitPrior));

        return new Window(bars, features, monthly);
    }

    private static TornStockMarketBar15mDO bar(int stocksId, double price) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setBarStartTime(T);
        bar.setBarEndTime(T.plusMinutes(15));
        bar.setLastPrice(BigDecimal.valueOf(price));
        bar.setUsable(true);
        bar.setBuildVersion("1.0.0");
        bar.setSourceMaxHistoryId(99L);
        return bar;
    }

    private static TornStockStrategyFeature15mDO feature(int stocksId, double return7d) {
        TornStockStrategyFeature15mDO feature = new TornStockStrategyFeature15mDO();
        feature.setStocksId(stocksId);
        feature.setBarStartTime(T);
        feature.setReturn7d(BigDecimal.valueOf(return7d));
        feature.setFeatureVersion("1.0.0");
        return feature;
    }

    private static TornStockMonthlyStateDO monthlyState(String strategyFitPrior) {
        TornStockMonthlyStateDO state = new TornStockMonthlyStateDO();
        state.setStocksId(STOCK_ID);
        state.setEffectiveMonth(MONTH);
        state.setStrategyFitPrior(strategyFitPrior);
        state.setMaturity("M2_PROVISIONAL");
        state.setRiskLevel("NONE");
        state.setSuggestedPersonality(strategyFitPrior);
        state.setManualOverride(false);
        state.setPersonalityRuleVersion("PERSONALITY_RULE_V1");
        state.setRiskRuleVersion("RISK_RULE_V1_SHADOW");
        return state;
    }

    private record Window(
            Map<Integer, NavigableMap<LocalDateTime, TornStockMarketBar15mDO>> bars,
            Map<Integer, NavigableMap<LocalDateTime, TornStockStrategyFeature15mDO>> features,
            Map<LocalDate, Map<Integer, TornStockMonthlyStateDO>> monthlyStates) {
    }
}
