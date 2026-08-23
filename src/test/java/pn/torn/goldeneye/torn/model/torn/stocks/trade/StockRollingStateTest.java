package pn.torn.goldeneye.torn.model.torn.stocks.trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.repository.model.torn.stocks.StockPricePoint;
import pn.torn.goldeneye.repository.model.torn.stocks.StockStrategyFeatureUpsert;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 股票滚动状态投资人数特征测试。
 *
 * @author Bai
 * @version 1.4.3
 * @since 2026.08.23
 */
@DisplayName("股票滚动状态投资人数特征测试")
class StockRollingStateTest {

    private static final LocalDateTime CURRENT_TIME = LocalDateTime.of(2026, 8, 23, 10, 0);

    @Test
    @DisplayName("当前投资人数未知_特征保留未知且不抛异常")
    void addAndCalculate_currentInvestorsUnknown_keepsInvestorFeaturesNull() {
        StockRollingState state = new StockRollingState();

        StockStrategyFeatureUpsert feature = state.addAndCalculate(point(CURRENT_TIME, null));

        assertNull(feature.latestInvestors());
        assertNull(feature.investorsChange7d());
    }

    @Test
    @DisplayName("七日基准投资人数未知_当前人数保留且变化量为未知")
    void addAndCalculate_historicalInvestorsUnknown_keepsChangeNull() {
        StockRollingState state = new StockRollingState();
        state.warmup(point(CURRENT_TIME.minusDays(7), null));

        StockStrategyFeatureUpsert feature = state.addAndCalculate(point(CURRENT_TIME, 120));

        assertEquals(120, feature.latestInvestors());
        assertNull(feature.investorsChange7d());
    }

    @Test
    @DisplayName("当前与七日基准投资人数已知_计算真实变化量")
    void addAndCalculate_bothInvestorsKnown_calculatesChange() {
        StockRollingState state = new StockRollingState();
        state.warmup(point(CURRENT_TIME.minusDays(7), 100));

        StockStrategyFeatureUpsert feature = state.addAndCalculate(point(CURRENT_TIME, 120));

        assertEquals(120, feature.latestInvestors());
        assertEquals(20, feature.investorsChange7d());
    }

    @Test
    @DisplayName("不存在七日基准但当前投资人数已知_保持既有零变化语义")
    void addAndCalculate_noHistoricalPoint_keepsZeroChange() {
        StockRollingState state = new StockRollingState();

        StockStrategyFeatureUpsert feature = state.addAndCalculate(point(CURRENT_TIME, 120));

        assertEquals(120, feature.latestInvestors());
        assertEquals(0, feature.investorsChange7d());
    }

    /**
     * 构造价格与投资人数时间点。
     *
     * @param time      时间
     * @param investors 投资人数；允许 {@code null} 表示外部历史源未知
     * @return 测试价格点
     */
    private StockPricePoint point(LocalDateTime time, Integer investors) {
        return new StockPricePoint(1L, 1, "TST", new BigDecimal("100.00"), investors, time);
    }
}
