package pn.torn.goldeneye.torn.service.stocks.alert.alpha.basis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.basis.StockAlphaPriceBasis.StockAlphaBasisInput;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.config.StockAlphaRuleDefinition;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.execution.StockAlphaExecutionBarPolicy.DecisionBar;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.market.StockAlphaDailyCloseCalculator.CloseResult;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.alpha.ranking.StockAlphaRankingResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * α价格口径测试。
 * <p>
 * 只覆盖两套口径的输入序列契约:观察口径把决策桶现价追加为新增一天并整体平移回溯窗口,
 * 现行口径序列不受观察口径影响,观察口径序列不完整时 fail-closed 返回空映射。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.09.18
 */
@DisplayName("α价格口径测试")
class StockAlphaPriceBasisTest {

    /**
     * 决策桶起点(生产为08:00桶)。
     */
    private static final LocalDateTime DECISION_BUCKET = LocalDateTime.of(2026, 9, 5, 8, 0);
    /**
     * 决策桶现价,必须与任一历史收盘不同以便识别窗口平移。
     */
    private static final BigDecimal DECISION_PRICE = new BigDecimal("130");
    /**
     * 历史共同有效日数量。
     */
    private static final int COMMON_DAYS = 21;

    @Test
    @DisplayName("观察口径_追加决策桶现价为新增一天并整体平移r1与r20窗口_现行口径不受影响")
    void latestPriceBasis_appendsCurrentBucketAsNewDay() {
        StockAlphaBasisInput input = basisInput();

        Map<Integer, List<BigDecimal>> previous =
                StockAlphaPriceBasisRegistry.productionBasis().closeSeries(input);
        Map<Integer, List<BigDecimal>> latest =
                StockAlphaPriceBasisRegistry.observationBasis().closeSeries(input);

        assertEquals(COMMON_DAYS, previous.get(1).size(), "现行口径必须严格使用已结束自然日序列");
        assertEquals(COMMON_DAYS + 1, latest.get(1).size(), "观察口径必须追加一天");
        assertEquals(0, DECISION_PRICE.compareTo(latest.get(1).getLast()), "追加值必须是决策桶现价");
        assertEquals(0, new BigDecimal("120").compareTo(previous.get(1).getLast()),
                "现行口径最后一天仍是前一日收盘,不得被现价覆盖");

        List<StockAlphaRankingResult> previousRankings = StockAlphaRankingCalculator.calculate(previous);
        List<StockAlphaRankingResult> latestRankings = StockAlphaRankingCalculator.calculate(latest);
        BigDecimal expectedLatestR1 = DECISION_PRICE.divide(new BigDecimal("120"),
                StockAlphaRuleDefinition.CALC_SCALE, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
        // 21个历史日 index 0..20 -> 追加后 last=21, last-20=1, 即回溯窗口整体平移一天
        BigDecimal expectedLatestR20 = DECISION_PRICE.divide(new BigDecimal("101"),
                StockAlphaRuleDefinition.CALC_SCALE, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
        BigDecimal expectedPreviousR20 = new BigDecimal("120").divide(new BigDecimal("100"),
                StockAlphaRuleDefinition.CALC_SCALE, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);

        assertEquals(0, expectedLatestR1.compareTo(r1Of(latestRankings)),
                "r1必须为现价除以前一日收盘");
        assertEquals(0, expectedLatestR20.compareTo(r20Of(latestRankings)),
                "r20的回溯窗口必须整体平移一天");
        assertEquals(0, expectedPreviousR20.compareTo(r20Of(previousRankings)),
                "现行口径r20窗口不得平移");

        // 观察口径不完整时fail-closed返回空映射,且失败不得影响生产口径
        Map<Integer, DecisionBar> incomplete = new HashMap<>(input.decisionBars());
        incomplete.remove(StockAlphaRuleDefinition.stockUniverse().getFirst());
        StockAlphaBasisInput broken = new StockAlphaBasisInput(input.rankingDates(), input.daily(), incomplete);
        assertTrue(StockAlphaPriceBasisRegistry.observationBasis().closeSeries(broken).isEmpty(),
                "成员不完整时观察口径必须fail-closed返回空映射");
        assertFalse(StockAlphaPriceBasisRegistry.productionBasis().closeSeries(broken).isEmpty(),
                "观察口径失败不得影响现行口径");
    }

    /**
     * 取排名向量中1号股票的1日收益。
     *
     * @param rankings 排名向量
     * @return 1日收益
     */
    private BigDecimal r1Of(List<StockAlphaRankingResult> rankings) {
        return rankings.stream().filter(result -> result.stocksId() == 1).findFirst()
                .orElseThrow().r1();
    }

    /**
     * 取排名向量中1号股票的20日收益。
     *
     * @param rankings 排名向量
     * @return 20日收益
     */
    private BigDecimal r20Of(List<StockAlphaRankingResult> rankings) {
        return rankings.stream().filter(result -> result.stocksId() == 1).findFirst()
                .orElseThrow().r20();
    }

    /**
     * 构造21个共同有效日、35支股票与决策桶现价的完整口径输入。
     * <p>
     * 收盘价按 100 + 日序号 同构,便于用确定的数值断言窗口平移。
     *
     * @return 口径输入
     */
    private StockAlphaBasisInput basisInput() {
        List<LocalDate> rankingDates = new ArrayList<>();
        Map<LocalDate, Map<Integer, CloseResult>> daily = new LinkedHashMap<>();
        for (int dayIndex = 0; dayIndex < COMMON_DAYS; dayIndex++) {
            LocalDate date = LocalDate.of(2026, 8, 1).plusDays(dayIndex);
            rankingDates.add(date);
            Map<Integer, CloseResult> byStock = new LinkedHashMap<>();
            for (Integer stocksId : StockAlphaRuleDefinition.stockUniverse()) {
                byStock.put(stocksId, new CloseResult(stocksId, date,
                        new BigDecimal(100 + dayIndex), dayIndex + 1L, date.atTime(23, 45)));
            }
            daily.put(date, byStock);
        }
        Map<Integer, DecisionBar> decisionBars = new LinkedHashMap<>();
        for (Integer stocksId : StockAlphaRuleDefinition.stockUniverse()) {
            decisionBars.put(stocksId, new DecisionBar(DECISION_BUCKET, DECISION_BUCKET.plusMinutes(15),
                    true, DECISION_PRICE));
        }
        return new StockAlphaBasisInput(rankingDates, daily, decisionBars);
    }
}
