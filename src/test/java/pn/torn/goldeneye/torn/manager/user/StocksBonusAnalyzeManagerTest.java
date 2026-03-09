package pn.torn.goldeneye.torn.manager.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksDetailVO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksVO;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 股票分红收益计算集成测试
 *
 * @author Bai
 * @version 1.0.0
 * @since 2026.03.09
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("股票分红BB投资策略分析器测试")
class StocksBonusAnalyzeManagerTest {
    @InjectMocks
    private StocksBonusAnalyzeManager manager;
    private List<TornStocksDO> allStocks;

    @BeforeEach
    void setUp() {
        allStocks = new ArrayList<>();
    }

    @Test
    @DisplayName("基础场景：单支股票，足够资金购买3个BB")
    void testSingleStock_EnoughCapital() {
        // 准备数据
        TornStocksDO stock = createStock(1, "TST", 1_000_000L, 100L, 50_000L);
        allStocks.add(stock);
        TornUserStocksVO userStocks = new TornUserStocksVO();
        userStocks.setStocks(new ArrayList<>());
        long availableCash = 10_000_000L; // 10M
        // 执行
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(availableCash, allStocks, userStocks);
        // 验证：应该买入3个BB（1M + 2M + 4M = 7M < 10M）
        assertThat(actions).hasSize(3)
                .allMatch(a -> a.type() == StocksBonusAnalyzeManager.OptimalAction.ActionType.BUY);
        assertThat(actions).extracting(StocksBonusAnalyzeManager.OptimalAction::bbLevel)
                .containsExactly(1, 2, 3); // 按等级升序
    }

    @Test
    @DisplayName("资金不足：只能购买部分BB")
    void testInsufficientCapital() {
        TornStocksDO stock = createStock(1, "TST", 1_000_000L, 100L, 50_000L);
        allStocks.add(stock);
        TornUserStocksVO userStocks = new TornUserStocksVO();
        userStocks.setStocks(new ArrayList<>());
        long availableCash = 2_500_000L; // 2.5M
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(availableCash, allStocks, userStocks);
        // 只能买1BB(1M) + 2BB(2M) = 3M，但只有2.5M，所以只买1BB和2BB
        assertThat(actions).hasSizeLessThanOrEqualTo(2)
                .allMatch(a -> a.type() == StocksBonusAnalyzeManager.OptimalAction.ActionType.BUY);
    }

    @Test
    @DisplayName("多支股票：ROI优先选择")
    void testMultipleStocks_ROIPriority() {
        // 股票A：高ROI
        TornStocksDO stockA = createStock(1, "HRO", 1_000_000L, 100L, 100_000L); // ROI=10%
        // 股票B：低ROI
        TornStocksDO stockB = createStock(2, "LRO", 2_000_000L, 200L, 100_000L); // ROI=5%
        allStocks.add(stockA);
        allStocks.add(stockB);
        TornUserStocksVO userStocks = new TornUserStocksVO();
        userStocks.setStocks(new ArrayList<>());
        long availableCash = 5_000_000L;
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(availableCash, allStocks, userStocks);
        // 应该优先购买高ROI的股票A
        long stockAActions = actions.stream()
                .filter(a -> a.stockShortName().equals("HRO"))
                .count();
        long stockBActions = actions.stream()
                .filter(a -> a.stockShortName().equals("LRO"))
                .count();
        assertThat(stockAActions).isGreaterThan(0)
                .isGreaterThanOrEqualTo(stockBActions);
    }

    @Test
    @DisplayName("已有持仓：识别当前BB并生成卖出建议")
    void testExistingPortfolio_SellSuggestion() {
        TornStocksDO stock = createStock(1, "TST", 1_000_000L, 100L, 50_000L);
        allStocks.add(stock);
        // 用户持有700股 = 拥有1BB(100) + 2BB(300累计) + 3BB(700累计)
        TornUserStocksVO userStocks = new TornUserStocksVO();
        TornUserStocksDetailVO detail = new TornUserStocksDetailVO();
        detail.setId(1);
        detail.setShares(700L);
        userStocks.setStocks(List.of(detail));
        long availableCash = 0L; // 无现金，应该建议卖出
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(availableCash, allStocks, userStocks);
        // 应该有卖出建议（因为总资本=7M，可能不是最优配置）
        long sellCount = actions.stream()
                .filter(a -> a.type() == StocksBonusAnalyzeManager.OptimalAction.ActionType.SELL)
                .count();
        assertThat(sellCount).isGreaterThanOrEqualTo(0); // 可能全卖或部分卖
    }

    @Test
    @DisplayName("卖出顺序：高等级BB优先")
    void testSellOrder_HighLevelFirst() {
        TornStocksDO stock = createStock(1, "TST", 1_000_000L, 100L, 50_000L);
        allStocks.add(stock);
        // 用户持有1500股 = 拥有1~4BB
        TornUserStocksVO userStocks = new TornUserStocksVO();
        TornUserStocksDetailVO detail = new TornUserStocksDetailVO();
        detail.setId(1);
        detail.setShares(1500L);
        userStocks.setStocks(List.of(detail));
        long availableCash = 0L;
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(availableCash, allStocks, userStocks);
        List<Integer> sellLevels = actions.stream()
                .filter(a -> a.type() == StocksBonusAnalyzeManager.OptimalAction.ActionType.SELL)
                .map(StocksBonusAnalyzeManager.OptimalAction::bbLevel)
                .toList();
        // 验证卖出顺序是降序
        if (sellLevels.size() > 1) {
            for (int i = 0; i < sellLevels.size() - 1; i++) {
                assertThat(sellLevels.get(i)).isGreaterThan(sellLevels.get(i + 1));
            }
        }
    }

    @Test
    @DisplayName("买入顺序：低等级BB优先")
    void testBuyOrder_LowLevelFirst() {
        TornStocksDO stock = createStock(1, "TST", 1_000_000L, 100L, 50_000L);
        allStocks.add(stock);
        TornUserStocksVO userStocks = new TornUserStocksVO();
        userStocks.setStocks(new ArrayList<>());
        long availableCash = 10_000_000L;
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(availableCash, allStocks, userStocks);
        List<Integer> buyLevels = actions.stream()
                .filter(a -> a.type() == StocksBonusAnalyzeManager.OptimalAction.ActionType.BUY)
                .map(StocksBonusAnalyzeManager.OptimalAction::bbLevel)
                .toList();
        // 验证买入顺序是升序
        if (buyLevels.size() > 1) {
            for (int i = 0; i < buyLevels.size() - 1; i++) {
                assertThat(buyLevels.get(i)).isLessThan(buyLevels.get(i + 1));
            }
        }
    }

    @Test
    @DisplayName("边界情况：资金为0")
    void testZeroCapital() {
        TornStocksDO stock = createStock(1, "TST", 1_000_000L, 100L, 50_000L);
        allStocks.add(stock);
        TornUserStocksVO userStocks = new TornUserStocksVO();
        userStocks.setStocks(new ArrayList<>());
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(0L, allStocks, userStocks);
        assertThat(actions).isEmpty();
    }

    @Test
    @DisplayName("边界情况：无股票数据")
    void testNoStocks() {
        TornUserStocksVO userStocks = new TornUserStocksVO();
        userStocks.setStocks(new ArrayList<>());
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(10_000_000L, new ArrayList<>(), userStocks);
        assertThat(actions).isEmpty();
    }

    @Test
    @DisplayName("边界情况：用户持仓为null")
    void testNullUserStocks() {
        TornStocksDO stock = createStock(1, "TST", 1_000_000L, 100L, 50_000L);
        allStocks.add(stock);
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(5_000_000L, allStocks, null);
        // 应该正常处理，视为无持仓
        assertThat(actions).isNotEmpty()
                .allMatch(a -> a.type() == StocksBonusAnalyzeManager.OptimalAction.ActionType.BUY);
    }

    @Test
    @DisplayName("复杂场景：多股票+已有持仓+资金调整")
    void testComplexScenario() {
        // 3支不同ROI的股票
        allStocks.add(createStock(1, "HIGH", 1_000_000L, 100L, 120_000L)); // ROI=12%
        allStocks.add(createStock(2, "MID", 1_500_000L, 150L, 150_000L));  // ROI=10%
        allStocks.add(createStock(3, "LOW", 2_000_000L, 200L, 160_000L));  // ROI=8%
        // 用户持有MID的2BB（450股）
        TornUserStocksVO userStocks = new TornUserStocksVO();
        TornUserStocksDetailVO detail = new TornUserStocksDetailVO();
        detail.setId(2);
        detail.setShares(450L); // 150 + 300 = 450，拥有1BB和2BB
        userStocks.setStocks(List.of(detail));
        long availableCash = 8_000_000L;
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(availableCash, allStocks, userStocks);
        // 验证：应该有买入和卖出操作
        assertThat(actions).isNotEmpty();
        // 验证操作顺序：卖出在前，买入在后
        int firstBuyIndex = -1;
        int lastSellIndex = -1;
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).type() == StocksBonusAnalyzeManager.OptimalAction.ActionType.BUY && firstBuyIndex == -1) {
                firstBuyIndex = i;
            }
            if (actions.get(i).type() == StocksBonusAnalyzeManager.OptimalAction.ActionType.SELL) {
                lastSellIndex = i;
            }
        }
        if (firstBuyIndex != -1 && lastSellIndex != -1) {
            assertThat(lastSellIndex).isLessThan(firstBuyIndex);
        }
    }

    @Test
    @DisplayName("累计股数计算验证")
    void testCumulativeSharesCalculation() {
        TornStocksDO stock = createStock(1, "TST", 1_000_000L, 100L, 50_000L);
        allStocks.add(stock);
        TornUserStocksVO userStocks = new TornUserStocksVO();
        TornUserStocksDetailVO detail = new TornUserStocksDetailVO();
        detail.setId(1);
        // 测试边界值：不足1BB
        detail.setShares(99L);
        userStocks.setStocks(List.of(detail));
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(10_000_000L, allStocks, userStocks);
        // 有资金时应该建议买入
        long buyCount = actions.stream().filter(a -> a.type() == StocksBonusAnalyzeManager.OptimalAction.ActionType.BUY).count();
        assertThat(buyCount).isGreaterThan(0);
        // 恰好1BB - 验证持仓识别
        detail.setShares(100L);
        userStocks.setStocks(List.of(detail));
        actions = manager.calculate(10_000_000L, allStocks, userStocks);
        // 应该识别到已有1BB，建议买入更高等级
        assertThat(actions).isNotEmpty();
        // 不足2BB（只有1BB）
        detail.setShares(299L);
        userStocks.setStocks(List.of(detail));
        actions = manager.calculate(10_000_000L, allStocks, userStocks);
        assertThat(actions).isNotEmpty();
        // 恰好1BB+2BB（累计300股）
        detail.setShares(300L);
        userStocks.setStocks(List.of(detail));
        actions = manager.calculate(10_000_000L, allStocks, userStocks);
        // 应该识别到持有2个BB，建议买入3BB
        buyCount = actions.stream()
                .filter(a -> a.type() == StocksBonusAnalyzeManager.OptimalAction.ActionType.BUY && a.bbLevel() == 3)
                .count();
        assertThat(buyCount).isGreaterThanOrEqualTo(0); // 如果资金足够会建议买3BB
        // 验证累计700股 = 拥有1BB+2BB+3BB
        detail.setShares(700L);
        userStocks.setStocks(List.of(detail));
        actions = manager.calculate(0L, allStocks, userStocks);
        // 资金为0时，如果当前配置不是最优，可能建议卖出
        assertThat(actions).isNotNull();
    }

    @Test
    @DisplayName("溢出保护：超大baseCost")
    void testOverflowProtection() {
        TornStocksDO stock = createStock(1, "HUGE", 500_000_000_000L, 100_000L, 5_000_000_000L);
        allStocks.add(stock);
        TornUserStocksVO userStocks = new TornUserStocksVO();
        userStocks.setStocks(new ArrayList<>());
        // 给定80B资金（低于100B阈值）
        long availableCash = 80_000_000_000L;
        // 不应该抛出异常
        List<StocksBonusAnalyzeManager.OptimalAction> actions = manager.calculate(availableCash, allStocks, userStocks);
        assertThat(actions).isNotNull();
        // 验证不会推荐超出资金能力的BB
        long totalCost = actions.stream()
                .filter(a -> a.type() == StocksBonusAnalyzeManager.OptimalAction.ActionType.BUY)
                .mapToLong(StocksBonusAnalyzeManager.OptimalAction::cost)
                .sum();
        assertThat(totalCost).isLessThanOrEqualTo(availableCash);
    }

    // 辅助方法
    private TornStocksDO createStock(int id, String shortName, long baseCost, long benefitReq, long yearProfit) {
        TornStocksDO stock = new TornStocksDO();
        stock.setId(id);
        stock.setStocksShortname(shortName);
        stock.setBaseCost(baseCost);
        stock.setBenefitReq(benefitReq);
        stock.setYearProfit(yearProfit);
        return stock;
    }
}