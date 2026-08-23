package pn.torn.goldeneye.torn.service.stocks.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回放输入内容摘要领域测试。
 *
 * <p>保护R2-P1-1/P1-3: 同一时间边界、行数与版本下,仅修改一行bar的任意冻结字段
 * (lastPrice/feature值/月度状态决策字段/sampleCount/lastSampleTime/barEndTime/lowPrice/
 * highPrice/qualityReason等),内容摘要必须变化;相同输入摘要可复算一致。该摘要纳入
 * sourceManifest hash,避免"同边界不同内容"被误判为同一次成功结果。冻结字段清单覆盖
 * {@link Stock15mBarBuildService#isUsable} 的全部输入,跨可用性边界的变更摘要与语义同步变化。</p>
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

    @Test
    @DisplayName("仅改sampleCount_行数边界不变_摘要变化")
    void changedSampleCount_digestChanges() {
        assertNotEquals(digestOf(buildWindow(100, 200, "NARROW")),
                digestOf(mutateBar(bar -> bar.setSampleCount(8))),
                "sampleCount变化必须改变摘要");
    }

    @Test
    @DisplayName("仅改lastSampleTime_行数边界不变_摘要变化")
    void changedLastSampleTime_digestChanges() {
        assertNotEquals(digestOf(buildWindow(100, 200, "NARROW")),
                digestOf(mutateBar(bar -> bar.setLastSampleTime(T.plusMinutes(3)))),
                "lastSampleTime变化必须改变摘要");
    }

    @Test
    @DisplayName("仅改barEndTime_行数边界不变_摘要变化")
    void changedBarEndTime_digestChanges() {
        assertNotEquals(digestOf(buildWindow(100, 200, "NARROW")),
                digestOf(mutateBar(bar -> bar.setBarEndTime(T.plusMinutes(16)))),
                "barEndTime变化必须改变摘要");
    }

    @Test
    @DisplayName("仅改lowPrice_行数边界不变_摘要变化")
    void changedLowPrice_digestChanges() {
        assertNotEquals(digestOf(buildWindow(100, 200, "NARROW")),
                digestOf(mutateBar(bar -> bar.setLowPrice(BigDecimal.valueOf(98.0)))),
                "lowPrice变化必须改变摘要");
    }

    @Test
    @DisplayName("仅改highPrice_行数边界不变_摘要变化")
    void changedHighPrice_digestChanges() {
        assertNotEquals(digestOf(buildWindow(100, 200, "NARROW")),
                digestOf(mutateBar(bar -> bar.setHighPrice(BigDecimal.valueOf(102.0)))),
                "highPrice变化必须改变摘要");
    }

    @Test
    @DisplayName("仅改qualityReason_行数边界不变_摘要变化")
    void changedQualityReason_digestChanges() {
        assertNotEquals(digestOf(buildWindow(100, 200, "NARROW")),
                digestOf(mutateBar(bar -> bar.setQualityReason("SAMPLE_INSUFFICIENT"))),
                "qualityReason变化必须改变摘要");
    }

    @Test
    @DisplayName("sampleCount跨isUsable边界_摘要与可用性语义均变化")
    void sampleCountCrossingUsableBoundary_changesDigest() {
        Window base = buildWindow(100, 200, "NARROW");
        Window below = mutateBar(bar -> bar.setSampleCount(8));
        assertNotEquals(digestOf(base), digestOf(below), "跨isUsable边界摘要必须变化");
        TornStockMarketBar15mDO belowBar = below.bars().get(STOCK_ID).firstEntry().getValue();
        assertEquals(Boolean.TRUE, base.bars().get(STOCK_ID).firstEntry().getValue().getUsable(),
                "基准bar应可用");
        assertFalse(Stock15mBarBuildService.isUsable(belowBar), "sampleCount=8应不可用");
        assertEquals(base.bars().size(), below.bars().size(), "行数不得变化");
    }

    @Test
    @DisplayName("lastSampleTime跨isUsable尾部新鲜度边界_摘要变化")
    void lastSampleTimeCrossingTailFreshness_digestsChange() {
        Window base = buildWindow(100, 200, "NARROW");
        Window stale = mutateBar(bar -> bar.setLastSampleTime(T.plusMinutes(9)));
        assertNotEquals(digestOf(base), digestOf(stale), "尾部新鲜度越界摘要必须变化");
        TornStockMarketBar15mDO staleBar = stale.bars().get(STOCK_ID).firstEntry().getValue();
        assertFalse(Stock15mBarBuildService.isUsable(staleBar), "尾部不新鲜应不可用");
    }

    private static String digestOf(StockReplayInputDigestTest.Window window) {
        return StockReplayInputDigest.compute(window.bars(), window.features(), window.monthlyStates());
    }

    /**
     * 对基准窗口的bar执行单字段变更并重新计算摘要。
     *
     * @param mutator bar变更回调
     * @return 变更后的窗口
     */
    private static Window mutateBar(java.util.function.Consumer<TornStockMarketBar15mDO> mutator) {
        Window base = buildWindow(100, 200, "NARROW");
        mutator.accept(base.bars().get(STOCK_ID).firstEntry().getValue());
        return base;
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
        bar.setFirstSampleTime(T.plusMinutes(1));
        bar.setLastSampleTime(T.plusMinutes(14));
        bar.setFirstPrice(BigDecimal.valueOf(price));
        bar.setLastPrice(BigDecimal.valueOf(price));
        bar.setLowPrice(BigDecimal.valueOf(price - 0.5));
        bar.setHighPrice(BigDecimal.valueOf(price + 0.5));
        bar.setSampleCount(15);
        bar.setDuplicateCount(2);
        bar.setTailGapSeconds(60);
        bar.setUsable(true);
        bar.setQualityReason(null);
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
