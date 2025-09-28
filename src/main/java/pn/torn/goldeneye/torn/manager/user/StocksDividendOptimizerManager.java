package pn.torn.goldeneye.torn.manager.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksDetailVO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksVO;
import pn.torn.goldeneye.utils.NumberUtils;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 股票分红计算逻辑层
 *
 * @author Bai
 * @version 0.2.0
 * @since 2025.09.27
 */
@Component
@Slf4j
public class StocksDividendOptimizerManager {
    // 设置一个合理的BB等级上限，因为成本会指数级增长，后续的ROI会变得极小
    private static final int MAX_BB_LEVEL_TO_CONSIDER = 10;
    // 动态规划中用于缩放资本的单位，防止DP数组过大。例如1,000,000代表1m
    private static final long CAPITAL_SCALE = 1_000_000L;

    /**
     * 计算最佳股票分红购买策略
     *
     * @param availableCash 用户的可用现金
     * @param allStocks     系统中所有股票的信息列表
     * @param userStocks    用户当前持有的股票信息
     * @return 返回一个包含买入和卖出建议的列表
     */
    public List<OptimalAction> calculate(long availableCash, List<TornStocksDO> allStocks, TornUserStocksVO userStocks) {
        log.info("开始计算最佳分红策略（目标：最大化年利润），初始现金: {}", NumberUtils.formatCompactNumber(availableCash));

        // 1. 生成所有可能的“单一BB”投资机会 (用于最终构建组合和比较)
        List<InvestmentOpportunity> allIndividualOpportunities = generateAllIndividualOpportunities(allStocks);

        // 2. 识别用户当前拥有的投资机会
        Set<InvestmentOpportunity> currentlyOwned = identifyOwnedOpportunities(allIndividualOpportunities, userStocks);
        log.info("用户当前持有 {} 个分红BB", currentlyOwned.size());
        long currentTotalProfit = currentlyOwned.stream().mapToLong(InvestmentOpportunity::yearProfit).sum();
        log.info("当前总年利润: {}", NumberUtils.formatCompactNumber(currentTotalProfit));

        // 3. 计算总资本 (可支配现金 + 已有投资的总成本)
        long ownedAssetsValue = currentlyOwned.stream().mapToLong(InvestmentOpportunity::cost).sum();
        long totalCapital = availableCash + ownedAssetsValue;
        log.info("用户总资本 (现金 + 已购成本): {} + {} = {}",
                NumberUtils.formatCompactNumber(availableCash),
                NumberUtils.formatCompactNumber(ownedAssetsValue),
                NumberUtils.formatCompactNumber(totalCapital));

        // 4. 使用动态规划构建以总利润为目标的理想投资组合
        Set<InvestmentOpportunity> targetPortfolio = buildTargetPortfolioByProfit(allIndividualOpportunities, allStocks, totalCapital);
        log.info("理想投资组合应包含 {} 个分红BB", targetPortfolio.size());
        long targetTotalProfit = targetPortfolio.stream().mapToLong(InvestmentOpportunity::yearProfit).sum();
        long targetTotalCost = targetPortfolio.stream().mapToLong(InvestmentOpportunity::cost).sum();
        log.info("理想投资组合目标总年利润: {} (成本: {})",
                NumberUtils.formatCompactNumber(targetTotalProfit), NumberUtils.formatCompactNumber(targetTotalCost));

        targetPortfolio.stream()
                .sorted(Comparator.comparing(InvestmentOpportunity::getUniqueId))
                .forEach(opp -> log.debug(" - 理想: {} (BB #{}), 成本: {}, 年利润: {}, ROI: {}%",
                        opp.stockShortName(), opp.bbLevel(), NumberUtils.formatCompactNumber(opp.cost()),
                        NumberUtils.formatCompactNumber(opp.yearProfit()), String.format("%.2f%%", opp.roi() * 100)));

        // 5. 比较当前与理想组合，生成操作建议
        return generateActions(currentlyOwned, targetPortfolio);
    }

    /**
     * 为所有股票生成所有可能的“单一BB”投资机会
     */
    private List<InvestmentOpportunity> generateAllIndividualOpportunities(List<TornStocksDO> allStocks) {
        List<InvestmentOpportunity> opportunities = new ArrayList<>();
        for (TornStocksDO stock : allStocks) {
            // TCI股票特殊处理，只能购买1BB
            int maxLevel = "TCI".equalsIgnoreCase(stock.getStocksShortname()) ? 1 : MAX_BB_LEVEL_TO_CONSIDER;

            for (int level = 1; level <= maxLevel; level++) {
                // 成本计算：baseCost * 2^(level - 1)
                long cost = stock.getBaseCost() * (1L << (level - 1));
                // 防止溢出
                if (cost < 0 || stock.getBaseCost() > 0 && cost <= 0) {
                    break;
                }

                double roi = (cost > 0) ? (double) stock.getYearProfit() / cost : 0;
                opportunities.add(new InvestmentOpportunity(
                        stock.getId(),
                        stock.getStocksShortname(),
                        level,
                        cost,
                        stock.getBenefitReq(), // 注意：这里是单个BB所需的股数，不是累计
                        stock.getYearProfit(),
                        roi));
            }
        }
        return opportunities;
    }

    /**
     * 根据用户持股情况，从所有机会中筛选出用户已拥有的部分
     */
    private Set<InvestmentOpportunity> identifyOwnedOpportunities(List<InvestmentOpportunity> allOpportunities,
                                                                  TornUserStocksVO userStocks) {
        if (userStocks == null || userStocks.getStocks() == null) {
            return Set.of();
        }

        Map<Integer, Integer> userIncrements = userStocks.getStocks().values().stream()
                .filter(detail -> detail.getDividend() != null && detail.getDividend().getIncrement() > 0)
                .collect(Collectors.toMap(TornUserStocksDetailVO::getStockId, detail -> detail.getDividend().getIncrement()));

        return allOpportunities.stream()
                .filter(opp -> userIncrements.getOrDefault(opp.stockId(), 0) >= opp.bbLevel())
                .collect(Collectors.toSet());
    }

    /**
     * 内部记录，用于DP计算，表示购买某股票到指定BB等级的“累计”成本和收益
     */
    private record CumulativeOpportunity(int stockId, int bbLevel, long totalCost, long totalProfit) {
    }

    /**
     * 使用动态规划（分组背包问题）构建最优的投资组合，目标是最大化总年利润
     */
    private Set<InvestmentOpportunity> buildTargetPortfolioByProfit(List<InvestmentOpportunity> allIndividualOpportunities,
                                                                    List<TornStocksDO> allStocks, long totalCapital) {

        // 1. 数据预处理：为每个股票生成“累计”购买选项
        Map<Integer, List<CumulativeOpportunity>> cumulativeOptionsByStock = new HashMap<>();
        for (TornStocksDO stock : allStocks) {
            List<CumulativeOpportunity> stockOptions = new ArrayList<>();
            long currentCumulativeCost = 0;
            long currentCumulativeProfit = 0;

            // 添加“不购买此股票”的选项
            stockOptions.add(new CumulativeOpportunity(stock.getId(), 0, 0, 0));

            int maxLevel = "TCI".equalsIgnoreCase(stock.getStocksShortname()) ? 1 : MAX_BB_LEVEL_TO_CONSIDER;
            for (int level = 1; level <= maxLevel; level++) {
                long levelCost = stock.getBaseCost() * (1L << (level - 1));
                if (levelCost < 0 || (stock.getBaseCost() > 0 && levelCost <= 0)) break; // 溢出检查

                currentCumulativeCost += levelCost;
                currentCumulativeProfit += stock.getYearProfit();
                if (currentCumulativeCost < 0) break; // 溢出检查

                stockOptions.add(new CumulativeOpportunity(stock.getId(), level, currentCumulativeCost, currentCumulativeProfit));
            }
            cumulativeOptionsByStock.put(stock.getId(), stockOptions);
        }

        // 2. 动态规划求解
        int scaledCapital = (int) (totalCapital / CAPITAL_SCALE);
        if (scaledCapital <= 0) {
            return Collections.emptySet();
        }

        long[] dp = new long[scaledCapital + 1]; // dp[w] = 使用w资本能获得的最大利润
        // solution[w] = Map<stockId, bbLevel> 记录在w资本下的最优投资组合
        Map<Integer, Integer>[] solution = new HashMap[scaledCapital + 1];
        for (int i = 0; i <= scaledCapital; i++) {
            solution[i] = new HashMap<>();
        }

        // 遍历每个物品组（即每支股票）
        for (TornStocksDO stock : allStocks) {
            int stockId = stock.getId();
            List<CumulativeOpportunity> options = cumulativeOptionsByStock.get(stockId);

            // 从最大资本开始向下遍历，确保每个物品组只被使用一次
            for (int w = scaledCapital; w >= 0; w--) {
                // 遍历组内每个物品（即每个累计购买选项）
                for (CumulativeOpportunity option : options) {
                    if (option.totalCost() == 0) continue; // 跳过“不购买”选项

                    int scaledCost = (int) (option.totalCost() / CAPITAL_SCALE);
                    if (w >= scaledCost) {
                        // 计算如果选择此选项，可能达到的新利润
                        long potentialNewProfit = dp[w - scaledCost] + option.totalProfit();

                        // 如果更优，则更新dp表和solution表
                        if (potentialNewProfit > dp[w]) {
                            dp[w] = potentialNewProfit;
                            // 记录决策：基于剩余资本的决策，并更新当前股票的决策
                            Map<Integer, Integer> newSolutionMap = new HashMap<>(solution[w - scaledCost]);
                            newSolutionMap.put(stockId, option.bbLevel());
                            solution[w] = newSolutionMap;
                        }
                    }
                }
            }
        }

        // 3. 回溯DP结果，构建目标投资组合
        Map<Integer, Integer> finalSolution = solution[scaledCapital];
        Set<InvestmentOpportunity> targetPortfolio = new HashSet<>();
        for (InvestmentOpportunity opp : allIndividualOpportunities) {
            int maxLevelForStock = finalSolution.getOrDefault(opp.stockId(), 0);
            if (opp.bbLevel() <= maxLevelForStock) {
                targetPortfolio.add(opp);
            }
        }

        return targetPortfolio;
    }


    /**
     * 比较当前组合和目标组合，生成买/卖建议
     */
    private List<OptimalAction> generateActions(Set<InvestmentOpportunity> owned, Set<InvestmentOpportunity> target) {
        Set<String> ownedIds = owned.stream().map(InvestmentOpportunity::getUniqueId).collect(Collectors.toSet());
        Set<String> targetIds = target.stream().map(InvestmentOpportunity::getUniqueId).collect(Collectors.toSet());

        // 卖出建议：在当前持有，但不在目标中
        // 规则：必须先卖出高等级的BB。例如要卖出1BB，必须先卖2BB。所以按bbLevel降序排列
        List<OptimalAction> sellActions = owned.stream()
                .filter(opp -> !targetIds.contains(opp.getUniqueId()))
                .map(opp -> new OptimalAction(OptimalAction.ActionType.SELL, opp.stockShortName(), opp.bbLevel(),
                        opp.cost(), opp.yearProfit(), opp.roi()))
                .sorted(Comparator.comparingInt(OptimalAction::bbLevel).reversed())
                .toList();

        // 买入建议：在目标中，但不在当前持有中
        // 规则：必须先买入低等级的BB。例如要买入2BB，必须先买1BB。所以按bbLevel升序排列
        List<OptimalAction> buyActions = target.stream()
                .filter(opp -> !ownedIds.contains(opp.getUniqueId()))
                .map(opp -> new OptimalAction(OptimalAction.ActionType.BUY, opp.stockShortName(), opp.bbLevel(),
                        opp.cost(), opp.yearProfit(), opp.roi()))
                .sorted(Comparator.comparingInt(OptimalAction::bbLevel).reversed()) // 优先买高ROI的
                .toList();

        log.info("生成 {} 条卖出建议和 {} 条买入建议。", sellActions.size(), buyActions.size());

        // 合并并返回结果，卖出操作在前，买入操作在后
        return Stream.concat(sellActions.stream(), buyActions.stream()).toList();
    }

    /**
     * 内部计算使用的投资机会表示
     *
     * @param stockId        股票ID
     * @param stockShortName 股票简称
     * @param bbLevel        BB等级，这是第几个BB (1, 2, 3...)
     * @param cost           花费资金，获取这个BB等级所需的花费
     * @param benefitReq     股数
     * @param yearProfit     年利润，这个BB带来的每年利润
     * @param roi            投资回报率 (yearProfit / cost)
     */
    record InvestmentOpportunity(
            int stockId,
            String stockShortName,
            int bbLevel,
            long cost,
            long benefitReq,
            long yearProfit,
            double roi) {
        public String getUniqueId() {
            return stockShortName + "-" + bbLevel;
        }
    }

    /**
     * 最终返回给用户的操作建议
     *
     * @param type           操作类型
     * @param stockShortName 股票简称
     * @param yearProfit     年利润
     * @param bbLevel        BB等级
     * @param cost           花费
     * @param roi            投资回报率
     */
    public record OptimalAction(
            ActionType type,
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