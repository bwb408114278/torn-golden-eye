package pn.torn.goldeneye.torn.manager.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.torn.manager.user.dividend.StockDividendKnapsackSolver;
import pn.torn.goldeneye.torn.manager.user.dividend.StockDividendOpportunityFactory;
import pn.torn.goldeneye.torn.manager.user.dividend.StockDividendOpportunityFactory.BbOpportunity;
import pn.torn.goldeneye.torn.manager.user.dividend.StockDividendOpportunityFactory.CumulativeOption;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksVO;
import pn.torn.goldeneye.utils.NumberUtils;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 股票分红计算逻辑层
 *
 * <p>负责分红BB组合的编排：预算口径、目标组合与当前组合比较、操作建议输出。
 * 机会与成本计算见 {@link StockDividendOpportunityFactory}，
 * 分组背包求解与预算校验见 {@link StockDividendKnapsackSolver}。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2025.09.27
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StocksBonusAnalyzeManager {
    private final StockDividendOpportunityFactory opportunityFactory;
    private final StockDividendKnapsackSolver knapsackSolver;

    /**
     * 计算最佳股票分红购买策略
     *
     * @param availableCash 用户可用现金
     * @param allStocks     待选股票列表
     * @param userStocks    用户当前持仓
     * @return 操作建议
     */
    public List<OptimalAction> calculate(long availableCash, List<TornStocksDO> allStocks, TornUserStocksVO userStocks) {
        return calculate(availableCash, allStocks, userStocks, false, Collections.emptySet());
    }

    /**
     * 计算最佳股票分红购买策略
     *
     * <p>模式说明：
     * <ul>
     *     <li>buyOnly = false：允许卖出重配，预算 = 现金 + 当前持仓已投入成本</li>
     *     <li>buyOnly = true：只允许新增购买/升级，预算 = 现金，仅做增量优化</li>
     * </ul>
     *
     * @param availableCash 用户可用现金
     * @param allStocks     待选股票列表
     * @param userStocks    用户当前持仓
     * @param buyOnly       是否只买不卖
     * @return 操作建议
     */
    public List<OptimalAction> calculate(long availableCash, List<TornStocksDO> allStocks, TornUserStocksVO userStocks,
                                         boolean buyOnly) {
        return calculate(availableCash, allStocks, userStocks, buyOnly, Collections.emptySet());
    }

    /**
     * 计算最佳股票分红购买策略（排除用户屏蔽的股票）
     *
     * <p>屏蔽口径：被屏蔽股票既不进入候选池，也不参与当前持仓识别，
     * 因此既不会给出买入建议也不会给出卖出建议，其已投入成本不计入本次预算。</p>
     *
     * @param availableCash    用户可用现金
     * @param allStocks        待选股票列表
     * @param userStocks       用户当前持仓
     * @param buyOnly          是否只买不卖
     * @param blockedStocksIds 用户屏蔽的股票ID集合, 为空表示不屏蔽
     * @return 操作建议; 无建议时返回空列表
     */
    public List<OptimalAction> calculate(long availableCash, List<TornStocksDO> allStocks, TornUserStocksVO userStocks,
                                         boolean buyOnly, Set<Integer> blockedStocksIds) {
        List<TornStocksDO> safeStocks = filterBlockedStocks(allStocks, blockedStocksIds);
        if (safeStocks.isEmpty()) {
            log.info("股票列表为空，直接返回空操作");
            return Collections.emptyList();
        }

        log.info("=== 开始计算最佳分红策略 ===");
        log.info("模式: {}", buyOnly ? "只买不卖" : "可买可卖");
        log.info("初始现金: {}", NumberUtils.formatCompactNumber(availableCash));

        List<BbOpportunity> allOpportunities = opportunityFactory.buildAllOpportunities(safeStocks);
        Set<BbOpportunity> currentPortfolio = opportunityFactory.identifyCurrentPortfolio(allOpportunities, userStocks);
        Map<Integer, Integer> currentLevels = opportunityFactory.extractMaxOwnedLevels(currentPortfolio);

        if (availableCash < 0) {
            if (buyOnly) {
                log.warn("只买不卖模式不支持负数金额，按 0 处理，原值={}", availableCash);
                availableCash = 0;
            } else {
                return calculateLowestProfitLossSellActions(availableCash, safeStocks, currentPortfolio, currentLevels);
            }
        }

        logPortfolioSummary("当前持仓", currentPortfolio);

        long investedCapital = sumCost(currentPortfolio);
        long totalCapital = buyOnly ? availableCash : opportunityFactory.safeAdd(availableCash, investedCapital);

        log.info("可用于优化的预算: {}", NumberUtils.formatCompactNumber(totalCapital));
        if (!buyOnly) {
            log.info("总资本: {} (现金: {} + 已投资: {})",
                    NumberUtils.formatCompactNumber(totalCapital),
                    NumberUtils.formatCompactNumber(availableCash),
                    NumberUtils.formatCompactNumber(investedCapital));
        } else {
            log.info("只买不卖模式：仅使用现金预算，不复用已投资资本");
        }

        Set<BbOpportunity> targetPortfolio = buildOptimalPortfolio(safeStocks, totalCapital, currentLevels, buyOnly);
        logPortfolioSummary("目标组合", targetPortfolio);

        List<OptimalAction> actions = generateActions(currentPortfolio, targetPortfolio, buyOnly);
        if (!buyOnly && !isTargetPortfolioBetter(currentPortfolio, targetPortfolio)) {
            log.info("目标组合收益未优于当前组合，保持当前持仓不变");
            return List.of();
        }

        long sellCount = actions.stream().filter(action -> action.type() == OptimalAction.ActionType.SELL).count();
        long buyCount = actions.stream().filter(action -> action.type() == OptimalAction.ActionType.BUY).count();
        log.info("=== 策略计算完成：{} 条卖出，{} 条买入 ===", sellCount, buyCount);

        return actions;
    }

    /**
     * 计算卖出指定资金时收益损失最低的操作
     *
     * @param availableCash    用户可用现金(负数)
     * @param allStocks        待选股票列表
     * @param currentPortfolio 当前持有的BB集合
     * @param currentLevels    当前每支股票的最高BB等级
     * @return 操作建议
     */
    private List<OptimalAction> calculateLowestProfitLossSellActions(long availableCash, List<TornStocksDO> allStocks,
                                                                     Set<BbOpportunity> currentPortfolio,
                                                                     Map<Integer, Integer> currentLevels) {
        long requiredCash = Math.abs(availableCash);
        if (requiredCash <= 0 || currentPortfolio.isEmpty()) {
            return Collections.emptyList();
        }

        long investedCapital = sumCost(currentPortfolio);
        if (requiredCash >= investedCapital) {
            return generateActions(currentPortfolio, Collections.emptySet(), false);
        }

        long targetCapital = investedCapital - requiredCash;
        Set<BbOpportunity> targetPortfolio = buildOptimalPortfolio(allStocks, targetCapital, currentLevels, false);
        logPortfolioSummary("负数金额目标组合", targetPortfolio);
        List<OptimalAction> actions = generateActions(currentPortfolio, targetPortfolio, false);

        long soldCapital = actions.stream()
                .filter(action -> action.type() == OptimalAction.ActionType.SELL)
                .mapToLong(OptimalAction::cost)
                .sum();
        long profitLoss = actions.stream()
                .filter(action -> action.type() == OptimalAction.ActionType.SELL)
                .mapToLong(OptimalAction::yearProfit)
                .sum();
        log.info("负数金额按卖出处理：目标卖出 {}, 实际卖出 {}, 收益损失 {}",
                NumberUtils.formatCompactNumber(requiredCash),
                NumberUtils.formatCompactNumber(soldCapital),
                NumberUtils.formatCompactNumber(profitLoss));
        return actions;
    }

    /**
     * 构建最优目标投资组合
     *
     * <p>普通模式使用绝对累计选项，预算为总资本；只买不卖模式使用相对当前持仓的增量选项，
     * 预算仅为现金，回溯出的等级是“最终等级”但成本/收益是增量口径。
     *
     * @param allStocks     待选股票列表
     * @param totalCapital  总预算
     * @param currentLevels 当前每支股票的最高BB等级
     * @param buyOnly       是否只买不卖
     * @return 目标投资组合
     */
    private Set<BbOpportunity> buildOptimalPortfolio(List<TornStocksDO> allStocks, long totalCapital,
                                                     Map<Integer, Integer> currentLevels, boolean buyOnly) {
        Map<Integer, List<CumulativeOption>> stockOptions = knapsackSolver.buildOptions(allStocks, currentLevels, buyOnly);
        Optional<StockDividendKnapsackSolver.DpResult> dpResult = knapsackSolver.solve(stockOptions, totalCapital);
        if (dpResult.isEmpty()) {
            return buyOnly ? opportunityFactory.backtrack(currentLevels, allStocks) : Collections.emptySet();
        }

        Map<Integer, Integer> optimalLevels = new HashMap<>();
        if (buyOnly) {
            optimalLevels.putAll(currentLevels);
        }
        optimalLevels.putAll(dpResult.get().optimalLevels());

        Set<BbOpportunity> portfolio = opportunityFactory.backtrack(optimalLevels, allStocks);
        if (!knapsackSolver.isWithinBudget(portfolio, currentLevels, totalCapital, buyOnly)) {
            log.warn("最终组合经过真实金额校验后超出预算，尝试回退校验结果");
        }

        return portfolio;
    }

    /**
     * 生成买卖操作建议
     *
     * <p>卖出按BB等级降序，买入按BB等级升序，保证操作顺序合理
     *
     * @param current 当前组合
     * @param target  目标组合
     * @param buyOnly 是否只买不卖
     * @return 操作建议
     */
    private List<OptimalAction> generateActions(Set<BbOpportunity> current,
                                                Set<BbOpportunity> target,
                                                boolean buyOnly) {
        Set<String> currentIds = current.stream().map(BbOpportunity::uniqueId).collect(Collectors.toSet());
        Set<String> targetIds = target.stream().map(BbOpportunity::uniqueId).collect(Collectors.toSet());

        List<OptimalAction> sellActions = buyOnly
                ? Collections.emptyList()
                : current.stream()
                .filter(opportunity -> !targetIds.contains(opportunity.uniqueId()))
                .map(opportunity -> new OptimalAction(
                        OptimalAction.ActionType.SELL, opportunity.stockShortName(), opportunity.bbLevel(),
                        opportunity.cost(), opportunity.yearProfit(), opportunity.roi()))
                .sorted(Comparator.comparingInt(OptimalAction::bbLevel).reversed())
                .toList();

        List<OptimalAction> buyActions = target.stream()
                .filter(opportunity -> !currentIds.contains(opportunity.uniqueId()))
                .map(opportunity -> new OptimalAction(
                        OptimalAction.ActionType.BUY, opportunity.stockShortName(), opportunity.bbLevel(),
                        opportunity.cost(), opportunity.yearProfit(), opportunity.roi()))
                .sorted(Comparator.comparingInt(OptimalAction::bbLevel))
                .toList();

        return Stream.concat(sellActions.stream(), buyActions.stream()).toList();
    }

    /**
     * 目标投资是否更优
     *
     * @param currentPortfolio 当前组合
     * @param targetPortfolio  目标组合
     * @return true为目标组合更优
     */
    private boolean isTargetPortfolioBetter(Set<BbOpportunity> currentPortfolio,
                                            Set<BbOpportunity> targetPortfolio) {
        long currentProfit = sumYearProfit(currentPortfolio);
        long targetProfit = sumYearProfit(targetPortfolio);

        if (targetProfit > currentProfit) {
            return true;
        }

        if (targetProfit < currentProfit) {
            return false;
        }

        long currentCost = sumCost(currentPortfolio);
        long targetCost = sumCost(targetPortfolio);

        return targetCost > currentCost;
    }

    /**
     * 打印投资组合摘要
     *
     * @param title     摘要标题
     * @param portfolio 投资组合
     */
    private void logPortfolioSummary(String title, Set<BbOpportunity> portfolio) {
        long totalProfit = sumYearProfit(portfolio);
        long totalCost = sumCost(portfolio);

        log.info("{}: {} 个BB, 总年利润: {}, 总成本: {}",
                title,
                portfolio.size(),
                NumberUtils.formatCompactNumber(totalProfit),
                NumberUtils.formatCompactNumber(totalCost));

        if (log.isDebugEnabled()) {
            portfolio.stream()
                    .sorted(Comparator.comparing(BbOpportunity::uniqueId))
                    .forEach(opportunity -> log.debug(
                            "  - {} ({}BB): 成本={}, 利润={}, ROI={}%",
                            opportunity.stockShortName(),
                            opportunity.bbLevel(),
                            NumberUtils.formatCompactNumber(opportunity.cost()),
                            NumberUtils.formatCompactNumber(opportunity.yearProfit()),
                            String.format("%.2f", opportunity.roi() * 100)));
        }
    }

    /**
     * 计算组合总年利润
     *
     * @param portfolio 投资组合
     * @return 组合总年利润
     */
    private long sumYearProfit(Set<BbOpportunity> portfolio) {
        return portfolio.stream().mapToLong(BbOpportunity::yearProfit).sum();
    }

    /**
     * 计算组合总成本
     *
     * @param portfolio 投资组合
     * @return 组合总成本
     */
    private long sumCost(Set<BbOpportunity> portfolio) {
        return portfolio.stream().mapToLong(BbOpportunity::cost).sum();
    }

    /**
     * 过滤用户屏蔽的股票
     *
     * @param allStocks        待选股票列表
     * @param blockedStocksIds 用户屏蔽的股票ID集合
     * @return 过滤后的股票列表; 入参为空时返回空列表
     */
    private List<TornStocksDO> filterBlockedStocks(List<TornStocksDO> allStocks, Set<Integer> blockedStocksIds) {
        List<TornStocksDO> safeStocks = Optional.ofNullable(allStocks).orElseGet(Collections::emptyList);
        if (CollectionUtils.isEmpty(blockedStocksIds)) {
            return safeStocks;
        }

        List<TornStocksDO> availableStocks = safeStocks.stream()
                .filter(stock -> stock != null && !blockedStocksIds.contains(stock.getId()))
                .toList();
        log.info("按屏蔽列表排除 {} 支股票, {} 支可参与计算",
                safeStocks.size() - availableStocks.size(), availableStocks.size());
        return availableStocks;
    }

    /**
     * 操作建议
     *
     * @param type           操作类型
     * @param stockShortName 股票简称
     * @param bbLevel        BB等级
     * @param cost           该BB单级成本
     * @param yearProfit     该BB带来的年利润
     * @param roi            投资回报率
     */
    public record OptimalAction(
            ActionType type,
            String stockShortName,
            int bbLevel,
            long cost,
            long yearProfit,
            double roi) {
        /**
         * 操作类型
         */
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
