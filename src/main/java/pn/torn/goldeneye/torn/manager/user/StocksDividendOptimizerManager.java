package pn.torn.goldeneye.torn.manager.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.torn.TornStocksDO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksDetailVO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksVO;

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

    /**
     * 计算最佳股票分红购买策略
     *
     * @param availableCash 用户的可用现金
     * @param allStocks     系统中所有股票的信息列表
     * @param userStocks    用户当前持有的股票信息
     * @return 返回一个包含买入和卖出建议的列表
     */
    public List<OptimalAction> calculate(long availableCash, List<TornStocksDO> allStocks, TornUserStocksVO userStocks) {
        log.info("开始计算最佳分红策略，初始现金: {}", availableCash);

        // 1. 生成所有可能的投资机会
        List<InvestmentOpportunity> allOpportunities = generateAllOpportunities(allStocks);

        // 2. 识别用户当前拥有的投资机会
        Set<InvestmentOpportunity> currentlyOwned = identifyOwnedOpportunities(allOpportunities, userStocks);
        log.info("用户当前持有 {} 个分红BB", currentlyOwned.size());
        currentlyOwned.stream()
                .sorted(Comparator.comparingDouble(InvestmentOpportunity::roi))
                .forEach(opp -> log.debug(" - 持有: {} (BB #{}) ROI: {}", opp.stockShortName(), opp.bbLevel(), opp.roi()));

        // 3. 计算总资本
        long ownedAssetsValue = currentlyOwned.stream().mapToLong(InvestmentOpportunity::cost).sum();
        long totalCapital = availableCash + ownedAssetsValue;
        log.info("用户总资本 (现金 + 已购成本): {} + {} = {}", availableCash, ownedAssetsValue, totalCapital);

        // 4. 构建理想的投资组合
        Set<InvestmentOpportunity> targetPortfolio = buildTargetPortfolio(allOpportunities, totalCapital);
        log.info("理想投资组合应包含 {} 个分红BB", targetPortfolio.size());
        targetPortfolio.stream()
                .sorted(Comparator.comparingDouble(InvestmentOpportunity::roi).reversed())
                .forEach(opp -> log.debug(" - 理想: {} (BB #{}) ROI: {}", opp.stockShortName(), opp.bbLevel(), opp.roi()));

        // 5. 比较当前与理想组合，生成操作建议
        return generateActions(currentlyOwned, targetPortfolio);
    }

    /**
     * 为所有股票生成所有可能的BB投资机会
     */
    private List<InvestmentOpportunity> generateAllOpportunities(List<TornStocksDO> allStocks) {
        List<InvestmentOpportunity> opportunities = new ArrayList<>();
        for (TornStocksDO stock : allStocks) {
            // TCI股票特殊处理，只能购买1BB
            int maxLevel = "TCI".equalsIgnoreCase(stock.getStocksShortname()) ? 1 : MAX_BB_LEVEL_TO_CONSIDER;

            for (int level = 1; level <= maxLevel; level++) {
                // 成本计算：baseCost * 2^(level - 1)
                long cost = stock.getBaseCost() * (1L << (level - 1));
                long benefitReq = stock.getBenefitReq() * (1L << (level - 1));
                // 防止溢出
                if (cost < 0) {
                    break;
                }

                double roi = (double) stock.getYearProfit() / cost;
                opportunities.add(new InvestmentOpportunity(
                        stock.getId(),
                        stock.getStocksShortname(),
                        level,
                        cost,
                        benefitReq,
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
     * 使用贪婪算法，根据总资本构建最优的投资组合
     */
    private Set<InvestmentOpportunity> buildTargetPortfolio(List<InvestmentOpportunity> allOpportunities, long totalCapital) {
        Set<InvestmentOpportunity> target = new HashSet<>();
        final long[] remainingCapital = {totalCapital};

        // 按ROI从高到低排序，贪婪地选择
        allOpportunities.stream()
                .sorted(Comparator.comparingDouble(InvestmentOpportunity::roi).reversed())
                .forEach(opp -> {
                    if (remainingCapital[0] >= opp.cost()) {
                        target.add(opp);
                        remainingCapital[0] -= opp.cost();
                    }
                });
        return target;
    }

    /**
     * 比较当前组合和目标组合，生成买/卖建议
     */
    private List<OptimalAction> generateActions(Set<InvestmentOpportunity> owned, Set<InvestmentOpportunity> target) {
        // 使用唯一ID进行比较
        Set<String> ownedIds = owned.stream().map(InvestmentOpportunity::getUniqueId).collect(Collectors.toSet());
        Set<String> targetIds = target.stream().map(InvestmentOpportunity::getUniqueId).collect(Collectors.toSet());

        // 卖出建议：在当前持有，但不在目标中
        List<OptimalAction> sellActions = owned.stream()
                .filter(opp -> !targetIds.contains(opp.getUniqueId()))
                .map(opp -> new OptimalAction(OptimalAction.ActionType.SELL, opp.stockShortName(), opp.bbLevel(),
                        opp.cost(), opp.benefitReq(), opp.roi()))
                .sorted(Comparator.comparingDouble(OptimalAction::roi)) // 建议优先卖出ROI最低的
                .toList();

        // 买入建议：在目标中，但不在当前持有中
        List<OptimalAction> buyActions = target.stream()
                .filter(opp -> !ownedIds.contains(opp.getUniqueId()))
                .map(opp -> new OptimalAction(OptimalAction.ActionType.BUY, opp.stockShortName(), opp.bbLevel(),
                        opp.cost(), opp.benefitReq(), opp.roi()))
                .sorted(Comparator.comparingDouble(OptimalAction::roi).reversed()) // 建议优先买入ROI最高的
                .toList();

        log.info("生成 {} 条卖出建议和 {} 条买入建议。", sellActions.size(), buyActions.size());

        // 合并并返回结果，通常卖出操作在前
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
     * @param dailyProfit    日利润，这个BB带来的每日利润
     * @param roi            投资回报率 (dailyProfit / cost)
     */
    record InvestmentOpportunity(
            int stockId,
            String stockShortName,
            int bbLevel,
            long cost,
            long benefitReq,
            long dailyProfit,
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
     * @param benefitReq     股数
     * @param bbLevel        BB等级
     * @param cost           花费
     * @param roi            投资回报率
     */
    public record OptimalAction(
            ActionType type,
            String stockShortName,
            int bbLevel,
            long cost,
            long benefitReq,
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
            return String.format("%s %dBB %s (股数: %d, 资金: %,d, ROI: %.2f%%)",
                    type.getDesc(), bbLevel, stockShortName, benefitReq, cost, roi * 100);
        }
    }
}