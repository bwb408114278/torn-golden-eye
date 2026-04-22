package pn.torn.goldeneye.torn.manager.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksDetailVO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksVO;
import pn.torn.goldeneye.utils.NumberUtils;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 股票分红计算逻辑层
 *
 * @author Bai
 * @version 1.0.0
 * @since 2025.09.27
 */
@Component
@Slf4j
public class StocksBonusAnalyzeManager {
    /**
     * BB等级上限（防止成本指数爆炸）
     */
    private static final int MAX_BB_LEVEL = 10;
    /**
     * DP资本缩放单位（1M = 1,000,000）
     * 用于将实际资本缩放到合理的数组索引范围
     */
    private static final long CAPITAL_SCALE_UNIT = 1_000_000L;

    /**
     * 计算最佳股票分红购买策略
     *
     * @param availableCash 用户可用现金
     * @param allStocks     所有股票信息
     * @param userStocks    用户持仓信息
     * @return 买卖操作建议列表（卖出在前，买入在后）
     */
    public List<OptimalAction> calculate(long availableCash, List<TornStocksDO> allStocks, TornUserStocksVO userStocks) {
        return calculate(availableCash, allStocks, userStocks, false);
    }

    /**
     * 计算最佳股票分红购买策略
     *
     * @param availableCash 用户可用现金
     * @param allStocks     所有股票信息
     * @param userStocks    用户持仓信息
     * @param buyOnly       只计算购买
     * @return 买卖操作建议列表（卖出在前，买入在后）
     */
    public List<OptimalAction> calculate(long availableCash, List<TornStocksDO> allStocks, TornUserStocksVO userStocks,
                                         boolean buyOnly) {
        log.info("=== 开始计算最佳分红策略 ===");
        log.info("初始现金: {}", NumberUtils.formatCompactNumber(availableCash));
        // 1. 生成所有可能的单一BB投资机会
        List<BBOpportunity> allOpportunities = generateAllBBOpportunities(allStocks);
        // 2. 识别用户当前持有的BB
        Set<BBOpportunity> currentPortfolio = identifyCurrentPortfolio(allOpportunities, userStocks);
        logPortfolioSummary("当前持仓", currentPortfolio);
        // 3. 计算总资本（现金 + 已投资成本）
        long investedCapital = currentPortfolio.stream().mapToLong(BBOpportunity::cost).sum();
        long totalCapital = buyOnly ? availableCash : availableCash + investedCapital;
        log.info("总资本: {} (现金: {} + 已投资: {})",
                NumberUtils.formatCompactNumber(totalCapital),
                NumberUtils.formatCompactNumber(availableCash),
                NumberUtils.formatCompactNumber(investedCapital));
        // 4. 使用DP构建最优投资组合
        Set<BBOpportunity> targetPortfolio = buildOptimalPortfolio(allStocks, totalCapital);
        logPortfolioSummary("目标组合", targetPortfolio);
        // 5. 生成操作建议
        List<OptimalAction> actions = generateActions(currentPortfolio, targetPortfolio, buyOnly);
        log.info("=== 策略计算完成：{} 条卖出，{} 条买入 ===",
                actions.stream().filter(a -> a.type() == OptimalAction.ActionType.SELL).count(),
                actions.stream().filter(a -> a.type() == OptimalAction.ActionType.BUY).count());
        return actions;
    }

    /**
     * 生成所有股票的所有可能BB投资机会
     */
    private List<BBOpportunity> generateAllBBOpportunities(List<TornStocksDO> allStocks) {
        List<BBOpportunity> opportunities = new ArrayList<>();
        for (TornStocksDO stock : allStocks) {
            for (int level = 1; level <= MAX_BB_LEVEL; level++) {
                // 计算第level个BB的成本和股数需求
                long cost = calculateBBCost(stock.getBaseCost(), level);
                long sharesRequired = calculateBBSharesRequired(stock.getBenefitReq(), level);
                // 溢出检查
                if (cost <= 0 || sharesRequired <= 0) {
                    log.warn("股票 {} 的第 {} BB 计算溢出，停止生成更高等级", stock.getStocksShortname(), level);
                    break;
                }
                double roi = (double) stock.getYearProfit() / cost;
                opportunities.add(new BBOpportunity(stock.getId(), stock.getStocksShortname(), level,
                        cost, sharesRequired, stock.getYearProfit(), roi));
            }
        }
        log.debug("生成 {} 个BB投资机会", opportunities.size());
        return opportunities;
    }

    /**
     * 根据用户持股数判断当前拥有的BB
     */
    private Set<BBOpportunity> identifyCurrentPortfolio(List<BBOpportunity> allOpportunities, TornUserStocksVO userStocks) {
        if (userStocks == null || userStocks.getStocks() == null) {
            return Collections.emptySet();
        }
        // 构建股票ID -> 持股数的映射
        Map<Integer, Long> stockIdToShares = userStocks.getStocks().stream()
                .collect(Collectors.toMap(
                        TornUserStocksDetailVO::getId,
                        TornUserStocksDetailVO::getShares,
                        // 处理重复key
                        (a, b) -> a));
        // 筛选用户拥有的BB
        Set<BBOpportunity> owned = new HashSet<>();
        for (BBOpportunity opp : allOpportunities) {
            long userShares = stockIdToShares.getOrDefault(opp.stockId(), 0L);
            long cumulativeSharesRequired = calculateCumulativeShares(opp.sharesRequiredForThisBB(), opp.bbLevel());
            if (userShares >= cumulativeSharesRequired) {
                owned.add(opp);
            }
        }
        return owned;
    }

    /**
     * 使用动态规划构建最优投资组合
     * <p>
     * 算法：分组背包DP
     * - dp[w] = 使用w单位资本能获得的最大年利润
     * - solution[w] = 在w单位资本下，每支股票应购买到第几个BB
     */
    private Set<BBOpportunity> buildOptimalPortfolio(List<TornStocksDO> allStocks, long totalCapital) {
        // 1. 为每支股票生成累计购买选项
        Map<Integer, List<CumulativeBBOption>> stockOptions = buildCumulativeOptions(allStocks);
        // 2. DP求解
        int scaledCapacity = scaleCapital(totalCapital);
        if (scaledCapacity <= 0) {
            log.warn("缩放后资本为0，无法进行DP计算");
            return Collections.emptySet();
        }
        DPResult dpResult = solveGroupedKnapsack(stockOptions, scaledCapacity);
        // 3. 回溯构建目标组合
        return backtrackSolution(dpResult.optimalLevels(), allStocks);
    }

    /**
     * 为每支股票构建累计购买选项
     * 例如：买到2BB = 买1BB + 买2BB，累计成本和利润
     */
    private Map<Integer, List<CumulativeBBOption>> buildCumulativeOptions(List<TornStocksDO> allStocks) {
        Map<Integer, List<CumulativeBBOption>> optionsMap = new HashMap<>();
        for (TornStocksDO stock : allStocks) {
            List<CumulativeBBOption> options = new ArrayList<>();
            options.add(new CumulativeBBOption(stock.getId(), 0, 0, 0));
            long cumulativeCost = 0;
            long cumulativeProfit = 0;
            for (int level = 1; level <= MAX_BB_LEVEL; level++) {
                long levelCost = calculateBBCost(stock.getBaseCost(), level);
                long newCumulativeCost = cumulativeCost + levelCost;
                // 合并溢出检查：单级成本溢出 或 累计成本溢出
                if (levelCost <= 0 || newCumulativeCost < 0) {
                    break;
                }
                cumulativeCost = newCumulativeCost;
                cumulativeProfit += stock.getYearProfit();
                options.add(new CumulativeBBOption(stock.getId(), level, cumulativeCost, cumulativeProfit));
            }
            optionsMap.put(stock.getId(), options);
        }
        return optionsMap;
    }

    /**
     * 分组背包DP求解
     */
    private DPResult solveGroupedKnapsack(Map<Integer, List<CumulativeBBOption>> stockOptions, int capacity) {
        long[] dp = new long[capacity + 1];
        @SuppressWarnings("unchecked")
        Map<Integer, Integer>[] solutions = new HashMap[capacity + 1];
        for (int i = 0; i <= capacity; i++) {
            solutions[i] = new HashMap<>();
        }
        for (Map.Entry<Integer, List<CumulativeBBOption>> entry : stockOptions.entrySet()) {
            processStockGroup(entry.getKey(), entry.getValue(), capacity, dp, solutions);
        }
        log.debug("DP求解完成，最大利润: {}", NumberUtils.formatCompactNumber(dp[capacity]));
        return new DPResult(solutions[capacity], dp[capacity]);
    }

    /**
     * 处理单个股票组的DP更新
     */
    private void processStockGroup(int stockId, List<CumulativeBBOption> options, int capacity,
                                   long[] dp, Map<Integer, Integer>[] solutions) {
        for (int w = capacity; w >= 0; w--) {
            for (CumulativeBBOption option : options) {
                if (option.level() == 0) continue;
                int scaledCost = scaleCapital(option.cumulativeCost());
                if (w >= scaledCost) {
                    updateDPState(w, scaledCost, stockId, option, dp, solutions);
                }
            }
        }
    }

    /**
     * 更新DP状态
     */
    private void updateDPState(int w, int scaledCost, int stockId, CumulativeBBOption option,
                               long[] dp, Map<Integer, Integer>[] solutions) {
        long newProfit = dp[w - scaledCost] + option.cumulativeProfit();
        if (newProfit > dp[w]) {
            dp[w] = newProfit;
            solutions[w] = new HashMap<>(solutions[w - scaledCost]);
            solutions[w].put(stockId, option.level());
        }
    }

    /**
     * 根据DP结果回溯构建目标投资组合
     */
    private Set<BBOpportunity> backtrackSolution(Map<Integer, Integer> optimalLevels, List<TornStocksDO> allStocks) {
        Set<BBOpportunity> portfolio = new HashSet<>();
        for (TornStocksDO stock : allStocks) {
            int maxLevel = optimalLevels.getOrDefault(stock.getId(), 0);
            for (int level = 1; level <= maxLevel; level++) {
                long cost = calculateBBCost(stock.getBaseCost(), level);
                if (cost <= 0) continue;

                long sharesRequired = calculateBBSharesRequired(stock.getBenefitReq(), level);
                double roi = (double) stock.getYearProfit() / cost;
                portfolio.add(new BBOpportunity(stock.getId(), stock.getStocksShortname(), level,
                        cost, sharesRequired, stock.getYearProfit(), roi));
            }
        }
        return portfolio;
    }

    /**
     * 生成买卖操作建议
     */
    private List<OptimalAction> generateActions(Set<BBOpportunity> current, Set<BBOpportunity> target, boolean buyOnly) {
        Set<String> currentIds = current.stream().map(BBOpportunity::uniqueId).collect(Collectors.toSet());
        Set<String> targetIds = target.stream().map(BBOpportunity::uniqueId).collect(Collectors.toSet());
        // 卖出：当前有但目标没有，按BB等级降序（先卖高级）
        List<OptimalAction> sellActions = buyOnly ? List.of() : current.stream()
                .filter(opp -> !targetIds.contains(opp.uniqueId()))
                .map(opp -> new OptimalAction(OptimalAction.ActionType.SELL, opp.stockShortName(),
                        opp.bbLevel(), opp.cost(), opp.yearProfit(), opp.roi()))
                .sorted(Comparator.comparingInt(OptimalAction::bbLevel).reversed())
                .toList();
        // 买入：目标有但当前没有，按BB等级升序（先买低级）
        List<OptimalAction> buyActions = target.stream()
                .filter(opp -> !currentIds.contains(opp.uniqueId()))
                .map(opp -> new OptimalAction(OptimalAction.ActionType.BUY, opp.stockShortName(),
                        opp.bbLevel(), opp.cost(), opp.yearProfit(), opp.roi()))
                .sorted(Comparator.comparingInt(OptimalAction::bbLevel))
                .toList();
        return Stream.concat(sellActions.stream(), buyActions.stream()).toList();
    }

    /**
     * 计算指数增长值（通用方法）
     * 公式：baseValue * 2^(level - 1)
     */
    private long calculateExponentialValue(long baseValue, int level) {
        if (level < 1) return 0;
        long multiplier = 1L << (level - 1);
        long result = baseValue * multiplier;
        // 溢出检查
        return (result < 0 || result / multiplier != baseValue) ? -1 : result;
    }

    /**
     * 计算第level个BB的成本
     */
    private long calculateBBCost(long baseCost, int level) {
        return calculateExponentialValue(baseCost, level);
    }

    /**
     * 计算第level个BB所需的股数
     */
    private long calculateBBSharesRequired(long benefitReq, int level) {
        return calculateExponentialValue(benefitReq, level);
    }

    /**
     * 计算拥有第level个BB所需的累计股数
     * 公式：benefitReq * (2^level - 1)
     * 例如：benefitReq=100, level=3 -> 100 * (8-1) = 700
     */
    private long calculateCumulativeShares(long sharesForThisBB, int level) {
        // 累计 = benefitReq * (1 + 2 + 4 + ... + 2^(level-1))
        //      = benefitReq * (2^level - 1)
        long multiplier = (1L << level) - 1; // 2^level - 1
        return sharesForThisBB * multiplier / (1L << (level - 1));
    }

    /**
     * 将实际资本缩放到DP数组索引
     */
    private int scaleCapital(long capital) {
        return (int) (capital / CAPITAL_SCALE_UNIT);
    }

    /**
     * 打印投资组合摘要
     */
    private void logPortfolioSummary(String title, Set<BBOpportunity> portfolio) {
        long totalProfit = portfolio.stream().mapToLong(BBOpportunity::yearProfit).sum();
        long totalCost = portfolio.stream().mapToLong(BBOpportunity::cost).sum();
        log.info("{}: {} 个BB, 总年利润: {}, 总成本: {}", title, portfolio.size(),
                NumberUtils.formatCompactNumber(totalProfit), NumberUtils.formatCompactNumber(totalCost));
        if (log.isDebugEnabled()) {
            portfolio.stream()
                    .sorted(Comparator.comparing(BBOpportunity::uniqueId))
                    .forEach(opp -> log.debug("  - {} ({}BB): 成本={}, 利润={}, ROI={}%",
                            opp.stockShortName(), opp.bbLevel(),
                            NumberUtils.formatCompactNumber(opp.cost()),
                            NumberUtils.formatCompactNumber(opp.yearProfit()),
                            String.format("%.2f", opp.roi() * 100)));
        }
    }

    /**
     * 单个BB投资机会
     *
     * @param stockId                 股票ID
     * @param stockShortName          股票简称
     * @param bbLevel                 BB等级（1, 2, 3...）
     * @param cost                    购买此BB的成本
     * @param sharesRequiredForThisBB 此BB单独需要的股数（非累计）
     * @param yearProfit              此BB带来的年利润
     * @param roi                     投资回报率
     */
    private record BBOpportunity(int stockId,
                                 String stockShortName,
                                 int bbLevel,
                                 long cost,
                                 long sharesRequiredForThisBB,
                                 long yearProfit,
                                 double roi) {
        public String uniqueId() {
            return stockShortName + "-" + bbLevel;
        }
    }

    /**
     * 累计购买选项（用于DP）
     *
     * @param stockId          股票ID
     * @param level            购买到第几个BB
     * @param cumulativeCost   累计成本
     * @param cumulativeProfit 累计利润
     */
    private record CumulativeBBOption(int stockId,
                                      int level,
                                      long cumulativeCost,
                                      long cumulativeProfit) {
    }

    /**
     * DP求解结果
     *
     * @param optimalLevels 每支股票的最优购买等级
     * @param maxProfit     最大利润
     */
    private record DPResult(Map<Integer, Integer> optimalLevels,
                            long maxProfit) {
    }

    /**
     * 操作建议
     */
    public record OptimalAction(ActionType type,
                                String stockShortName,
                                int bbLevel,
                                long cost,
                                long yearProfit,
                                double roi) {
        @Getter
        @AllArgsConstructor
        public enum ActionType {
            BUY("买入"),
            SELL("卖出");
            private final String desc;
        }

        @Override
        @Nonnull
        public String toString() {
            return String.format("%s %dBB %s (年化收益: %s, 资金: %s, ROI: %.2f%%)",
                    type.getDesc(),
                    bbLevel,
                    stockShortName,
                    NumberUtils.formatCompactNumber(yearProfit),
                    NumberUtils.formatCompactNumber(cost),
                    roi * 100);
        }
    }
}