package pn.torn.goldeneye.torn.manager.user.dividend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.torn.manager.user.dividend.StockDividendOpportunityFactory.BbOpportunity;
import pn.torn.goldeneye.torn.manager.user.dividend.StockDividendOpportunityFactory.CumulativeOption;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 股票分红BB组合分组背包求解器
 *
 * <p>把每支股票当作一组、每个BB等级当作组内选项，求预算内的最大年利润组合。
 * 只做选项构建、DP求解与预算校验，不产出面向用户的文案。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StockDividendKnapsackSolver {
    /**
     * DP资本缩放单位（1M = 1,000,000）
     * 预算按 floor，成本按 ceil，避免因缩放误差造成“理论可买、实际超买”
     */
    private static final long CAPITAL_SCALE_UNIT = 1_000_000L;

    private final StockDividendOpportunityFactory opportunityFactory;

    /**
     * 构建DP分组选项
     *
     * <p>普通模式使用绝对累计选项；只买不卖模式使用相对当前持仓的增量选项。
     *
     * @param allStocks     待选股票列表
     * @param currentLevels 每支股票当前已拥有的最高BB等级
     * @param buyOnly       是否只买不卖
     * @return 股票ID到组内选项列表的映射
     */
    public Map<Integer, List<CumulativeOption>> buildOptions(List<TornStocksDO> allStocks,
                                                             Map<Integer, Integer> currentLevels,
                                                             boolean buyOnly) {
        return buyOnly
                ? buildIncrementalOptions(allStocks, currentLevels)
                : buildAbsoluteOptions(allStocks);
    }

    /**
     * 求解预算内的最优等级组合
     *
     * @param stockOptions 股票ID到组内选项列表的映射
     * @param totalCapital 总预算
     * @return 求解结果; 缩放后预算为0时返回空
     */
    public Optional<DpResult> solve(Map<Integer, List<CumulativeOption>> stockOptions, long totalCapital) {
        int scaledCapacity = scaleBudget(totalCapital);
        if (scaledCapacity <= 0) {
            log.warn("缩放后预算为 0，无法进行 DP 计算");
            return Optional.empty();
        }

        return Optional.of(solveGroupedKnapsack(stockOptions, scaledCapacity));
    }

    /**
     * 校验最终组合是否在真实预算内
     *
     * <p>普通模式：按组合总成本 <= 总资本判断
     * <p>只买不卖模式：按“目标组合总成本 - 当前组合总成本” <= 现金预算判断
     *
     * @param targetPortfolio 目标组合
     * @param currentLevels   当前每支股票的最高等级
     * @param budget          总预算
     * @param buyOnly         是否只买不卖
     * @return true为在预算内
     */
    public boolean isWithinBudget(Set<BbOpportunity> targetPortfolio,
                                  Map<Integer, Integer> currentLevels,
                                  long budget,
                                  boolean buyOnly) {
        long targetCost = sumCost(targetPortfolio);
        if (!buyOnly) {
            return targetCost <= budget;
        }

        long currentCost = calculatePortfolioCostByLevels(currentLevels, targetPortfolio);
        long incrementalCost = targetCost - currentCost;
        return incrementalCost <= budget;
    }

    /**
     * 构建普通模式使用的“绝对累计选项”
     *
     * @param allStocks 待选股票列表
     * @return 股票ID到绝对累计选项列表的映射
     */
    private Map<Integer, List<CumulativeOption>> buildAbsoluteOptions(List<TornStocksDO> allStocks) {
        Map<Integer, List<CumulativeOption>> optionsMap = new HashMap<>();
        for (TornStocksDO stock : allStocks) {
            if (opportunityFactory.isInvalidStock(stock)) {
                continue;
            }
            optionsMap.put(stock.getId(), opportunityFactory.buildAbsoluteOptions(stock));
        }
        return optionsMap;
    }

    /**
     * 构建只买不卖模式使用的“增量选项”
     *
     * <p>假设当前已持有2BB，则生成：
     * <ul>
     *     <li>2BB -> 增量成本0，增量利润0</li>
     *     <li>3BB -> 增量成本=累计3BB-累计2BB，增量利润=累计3BB-累计2BB</li>
     * </ul>
     *
     * <p>注意：option.level 仍表示最终等级，便于最终回溯。
     *
     * @param allStocks     待选股票列表
     * @param currentLevels 每支股票当前已拥有的最高BB等级
     * @return 股票ID到增量选项列表的映射
     */
    private Map<Integer, List<CumulativeOption>> buildIncrementalOptions(List<TornStocksDO> allStocks,
                                                                         Map<Integer, Integer> currentLevels) {
        Map<Integer, List<CumulativeOption>> optionsMap = new HashMap<>();

        for (TornStocksDO stock : allStocks) {
            if (opportunityFactory.isInvalidStock(stock)) {
                continue;
            }

            List<CumulativeOption> absoluteOptions = opportunityFactory.buildAbsoluteOptions(stock);
            int currentLevel = currentLevels.getOrDefault(stock.getId(), 0);
            CumulativeOption baseOption = findOptionByLevel(absoluteOptions, currentLevel);

            long baseCost = baseOption.cumulativeCost();
            long baseProfit = baseOption.cumulativeProfit();

            List<CumulativeOption> incrementalOptions = absoluteOptions.stream()
                    .filter(option -> option.level() >= currentLevel)
                    .map(option -> new CumulativeOption(
                            stock.getId(),
                            option.level(),
                            option.cumulativeCost() - baseCost,
                            option.cumulativeProfit() - baseProfit))
                    .toList();

            optionsMap.put(stock.getId(), incrementalOptions);
        }

        return optionsMap;
    }

    /**
     * 从选项中按等级查找目标项，找不到则返回0级
     *
     * @param options 选项列表
     * @param level   目标等级
     * @return 目标等级选项; 不存在时返回0级选项
     */
    private CumulativeOption findOptionByLevel(List<CumulativeOption> options, int level) {
        return options.stream()
                .filter(option -> option.level() == level)
                .findFirst()
                .orElse(new CumulativeOption(options.isEmpty() ? 0 : options.getFirst().stockId(),
                        0, 0, 0));
    }

    /**
     * 分组背包DP求解
     *
     * <p>每支股票是一组，每组只能选一个最终等级
     *
     * @param stockOptions 股票ID到组内选项列表的映射
     * @param capacity     缩放后的容量
     * @return 求解结果
     */
    private DpResult solveGroupedKnapsack(Map<Integer, List<CumulativeOption>> stockOptions, int capacity) {
        long[] dp = new long[capacity + 1];

        @SuppressWarnings("unchecked")
        Map<Integer, Integer>[] solutions = new HashMap[capacity + 1];
        for (int i = 0; i <= capacity; i++) {
            solutions[i] = new HashMap<>();
        }

        for (Map.Entry<Integer, List<CumulativeOption>> entry : stockOptions.entrySet()) {
            processStockGroup(entry.getKey(), entry.getValue(), capacity, dp, solutions);
        }

        int bestIndex = 0;
        long maxProfit = dp[0];
        for (int w = 1; w <= capacity; w++) {
            if (dp[w] > maxProfit) {
                maxProfit = dp[w];
                bestIndex = w;
            }
        }
        log.debug("DP求解完成，最大利润: {}, 最优容量位置: {}",
                NumberUtils.formatCompactNumber(maxProfit), bestIndex);
        return new DpResult(new HashMap<>(solutions[bestIndex]), maxProfit);
    }

    /**
     * 处理单个股票组的DP状态转移
     *
     * @param stockId   股票ID
     * @param options   该股票的组内选项
     * @param capacity  缩放后的容量
     * @param dp        当前DP数组
     * @param solutions 当前每个容量下的等级方案
     */
    private void processStockGroup(int stockId,
                                   List<CumulativeOption> options,
                                   int capacity,
                                   long[] dp,
                                   Map<Integer, Integer>[] solutions) {
        long[] newDp = Arrays.copyOf(dp, capacity + 1);

        @SuppressWarnings("unchecked")
        Map<Integer, Integer>[] newSolutions = new HashMap[capacity + 1];
        for (int i = 0; i <= capacity; i++) {
            newSolutions[i] = new HashMap<>(solutions[i]);
        }

        for (int w = 0; w <= capacity; w++) {
            for (CumulativeOption option : options) {
                int scaledCost = scaleCost(option.cumulativeCost());
                if (w < scaledCost) {
                    continue;
                }

                long newProfit = dp[w - scaledCost] + option.cumulativeProfit();
                if (newProfit > newDp[w]) {
                    newDp[w] = newProfit;
                    newSolutions[w] = new HashMap<>(solutions[w - scaledCost]);
                    newSolutions[w].put(stockId, option.level());
                }
            }
        }

        System.arraycopy(newDp, 0, dp, 0, capacity + 1);
        System.arraycopy(newSolutions, 0, solutions, 0, capacity + 1);
    }

    /**
     * 预算缩放：向下取整
     *
     * @param capital 预算
     * @return 缩放后的容量
     */
    private int scaleBudget(long capital) {
        if (capital <= 0) {
            return 0;
        }
        long scaled = capital / CAPITAL_SCALE_UNIT;
        return scaled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    /**
     * 成本缩放：向上取整，避免缩放误差导致超买
     *
     * @param cost 成本
     * @return 缩放后的成本
     */
    private int scaleCost(long cost) {
        if (cost <= 0) {
            return 0;
        }
        long scaled = (cost + CAPITAL_SCALE_UNIT - 1) / CAPITAL_SCALE_UNIT;
        return scaled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    /**
     * 计算组合总成本
     *
     * @param portfolio 组合
     * @return 组合总成本
     */
    private long sumCost(Set<BbOpportunity> portfolio) {
        return portfolio.stream().mapToLong(BbOpportunity::cost).sum();
    }

    /**
     * 根据当前等级映射计算已持仓成本
     *
     * @param levels          当前每支股票的最高等级
     * @param targetPortfolio 目标组合
     * @return 已持仓成本
     */
    private long calculatePortfolioCostByLevels(Map<Integer, Integer> levels, Set<BbOpportunity> targetPortfolio) {
        if (levels == null || levels.isEmpty()) {
            return 0L;
        }

        Map<Integer, Integer> levelMap = new HashMap<>(levels);
        long total = 0L;

        Map<Integer, List<BbOpportunity>> grouped = targetPortfolio.stream()
                .collect(Collectors.groupingBy(BbOpportunity::stockId));

        for (Map.Entry<Integer, Integer> entry : levelMap.entrySet()) {
            int stockId = entry.getKey();
            int maxLevel = entry.getValue();
            List<BbOpportunity> list = grouped.getOrDefault(stockId, Collections.emptyList());
            for (BbOpportunity opportunity : list) {
                if (opportunity.bbLevel() <= maxLevel) {
                    total += opportunity.cost();
                }
            }
        }

        return total;
    }

    /**
     * DP求解结果
     *
     * @param optimalLevels 每支股票最终应达到的最优等级
     * @param maxProfit     最大利润
     */
    public record DpResult(
            Map<Integer, Integer> optimalLevels,
            long maxProfit) {
    }
}
