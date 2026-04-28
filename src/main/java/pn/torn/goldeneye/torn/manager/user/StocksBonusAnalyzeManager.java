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
     * BB等级上限，防止指数增长导致计算规模和金额失控
     */
    private static final int MAX_BB_LEVEL = 10;
    /**
     * DP资本缩放单位（1M = 1,000,000）
     * 预算按 floor，成本按 ceil，避免因缩放误差造成“理论可买、实际超买”
     */
    private static final long CAPITAL_SCALE_UNIT = 1_000_000L;

    /**
     * 计算最佳股票分红购买策略
     *
     * @param availableCash 用户可用现金
     */
    public List<OptimalAction> calculate(long availableCash, List<TornStocksDO> allStocks, TornUserStocksVO userStocks) {
        return calculate(availableCash, allStocks, userStocks, false);
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
     * @param buyOnly       是否只买不卖
     */
    public List<OptimalAction> calculate(long availableCash, List<TornStocksDO> allStocks, TornUserStocksVO userStocks,
                                         boolean buyOnly) {
        if (availableCash < 0) {
            log.warn("availableCash 小于 0，按 0 处理，原值={}", availableCash);
            availableCash = 0;
        }

        List<TornStocksDO> safeStocks = Optional.ofNullable(allStocks).orElseGet(Collections::emptyList);
        if (safeStocks.isEmpty()) {
            log.info("股票列表为空，直接返回空操作");
            return Collections.emptyList();
        }

        log.info("=== 开始计算最佳分红策略 ===");
        log.info("模式: {}", buyOnly ? "只买不卖" : "可买可卖");
        log.info("初始现金: {}", NumberUtils.formatCompactNumber(availableCash));

        List<BBOpportunity> allOpportunities = generateAllBbOpportunities(safeStocks);
        Set<BBOpportunity> currentPortfolio = identifyCurrentPortfolio(allOpportunities, userStocks);
        Map<Integer, Integer> currentLevels = extractMaxOwnedLevels(currentPortfolio);

        logPortfolioSummary("当前持仓", currentPortfolio);

        long investedCapital = sumCost(currentPortfolio);
        long totalCapital = buyOnly ? availableCash : safeAdd(availableCash, investedCapital);

        log.info("可用于优化的预算: {}", NumberUtils.formatCompactNumber(totalCapital));
        if (!buyOnly) {
            log.info("总资本: {} (现金: {} + 已投资: {})",
                    NumberUtils.formatCompactNumber(totalCapital),
                    NumberUtils.formatCompactNumber(availableCash),
                    NumberUtils.formatCompactNumber(investedCapital));
        } else {
            log.info("只买不卖模式：仅使用现金预算，不复用已投资资本");
        }

        Set<BBOpportunity> targetPortfolio = buildOptimalPortfolio(safeStocks, totalCapital, currentLevels, buyOnly);
        logPortfolioSummary("目标组合", targetPortfolio);

        List<OptimalAction> actions = generateActions(currentPortfolio, targetPortfolio, buyOnly);

        long sellCount = actions.stream().filter(action -> action.type() == OptimalAction.ActionType.SELL).count();
        long buyCount = actions.stream().filter(action -> action.type() == OptimalAction.ActionType.BUY).count();
        log.info("=== 策略计算完成：{} 条卖出，{} 条买入 ===", sellCount, buyCount);

        return actions;
    }

    /**
     * 生成所有股票所有可能的单个BB机会
     */
    private List<BBOpportunity> generateAllBbOpportunities(List<TornStocksDO> allStocks) {
        List<BBOpportunity> opportunities = new ArrayList<>();
        for (TornStocksDO stock : allStocks) {
            if (isInvalidStock(stock)) {
                log.warn("股票数据无效，跳过，stockId={}, shortName={}",
                        stock == null ? null : stock.getId(),
                        stock == null ? null : stock.getStocksShortname());
                continue;
            }

            for (int level = 1; level <= MAX_BB_LEVEL; level++) {
                long cost = calculateBbCost(stock.getBaseCost(), level);
                long sharesRequired = calculateBbSharesRequired(stock.getBenefitReq(), level);
                if (cost <= 0 || sharesRequired <= 0) {
                    log.warn("股票 {} 的第 {} BB 计算溢出或非法，停止生成更高等级",
                            stock.getStocksShortname(), level);
                    break;
                }

                double roi = calculateRoi(stock.getYearProfit(), cost);
                opportunities.add(new BBOpportunity(stock.getId(), stock.getStocksShortname(), level, cost,
                        sharesRequired, stock.getYearProfit(), roi));
            }
        }

        log.debug("生成 {} 个 BB 投资机会", opportunities.size());
        return opportunities;
    }

    /**
     * 根据用户持股数识别当前拥有的BB集合
     */
    private Set<BBOpportunity> identifyCurrentPortfolio(List<BBOpportunity> allOpportunities,
                                                        TornUserStocksVO userStocks) {
        if (userStocks == null || userStocks.getStocks() == null || userStocks.getStocks().isEmpty()) {
            return Set.of();
        }

        Map<Integer, Long> stockIdToShares = userStocks.getStocks().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        TornUserStocksDetailVO::getId,
                        TornUserStocksDetailVO::getShares,
                        Math::max));

        Set<BBOpportunity> owned = new HashSet<>();
        for (BBOpportunity opportunity : allOpportunities) {
            long userShares = stockIdToShares.getOrDefault(opportunity.stockId(), 0L);
            long cumulativeSharesRequired = calculateCumulativeShares(
                    opportunity.sharesRequiredForThisBb(),
                    opportunity.bbLevel()
            );
            if (userShares >= cumulativeSharesRequired) {
                owned.add(opportunity);
            }
        }
        return owned;
    }

    /**
     * 提取用户每支股票当前已拥有的最高BB等级
     */
    private Map<Integer, Integer> extractMaxOwnedLevels(Set<BBOpportunity> currentPortfolio) {
        if (currentPortfolio == null || currentPortfolio.isEmpty()) {
            return Collections.emptyMap();
        }

        return currentPortfolio.stream().collect(Collectors.toMap(
                BBOpportunity::stockId,
                BBOpportunity::bbLevel,
                Math::max));
    }

    /**
     * 构建最优目标投资组合
     *
     * <p>普通模式：
     * <ul>
     *     <li>使用绝对累计选项</li>
     *     <li>预算为总资本（现金 + 已投资可回收部分）</li>
     * </ul>
     *
     * <p>只买不卖模式：
     * <ul>
     *     <li>使用相对当前持仓的增量选项</li>
     *     <li>预算仅为现金</li>
     *     <li>结果等级是“最终等级”，但成本/收益是“增量成本/增量收益”</li>
     * </ul>
     */
    private Set<BBOpportunity> buildOptimalPortfolio(List<TornStocksDO> allStocks, long totalCapital,
                                                     Map<Integer, Integer> currentLevels, boolean buyOnly) {
        Map<Integer, List<CumulativeBbOption>> stockOptions = buyOnly
                ? buildIncrementalOptions(allStocks, currentLevels)
                : buildAbsoluteOptions(allStocks);

        int scaledCapacity = scaleBudget(totalCapital);
        if (scaledCapacity <= 0) {
            log.warn("缩放后预算为 0，无法进行 DP 计算");
            return buyOnly ? backtrackSolution(currentLevels, allStocks) : Collections.emptySet();
        }

        DpResult dpResult = solveGroupedKnapsack(stockOptions, scaledCapacity);

        Map<Integer, Integer> optimalLevels = new HashMap<>();
        if (buyOnly) {
            optimalLevels.putAll(currentLevels);
        }
        optimalLevels.putAll(dpResult.optimalLevels());

        Set<BBOpportunity> portfolio = backtrackSolution(optimalLevels, allStocks);

        if (!isWithinBudget(portfolio, currentLevels, totalCapital, buyOnly)) {
            log.warn("最终组合经过真实金额校验后超出预算，尝试回退校验结果");
        }

        return portfolio;
    }

    /**
     * 构建普通模式使用的“绝对累计选项”
     *
     * <p>例如：
     * <ul>
     *     <li>0BB -> 成本0，利润0</li>
     *     <li>1BB -> 累计成本500m，累计利润100m</li>
     *     <li>2BB -> 累计成本1.5b，累计利润200m</li>
     * </ul>
     */
    private Map<Integer, List<CumulativeBbOption>> buildAbsoluteOptions(List<TornStocksDO> allStocks) {
        Map<Integer, List<CumulativeBbOption>> optionsMap = new HashMap<>();
        for (TornStocksDO stock : allStocks) {
            if (isInvalidStock(stock)) {
                continue;
            }
            optionsMap.put(stock.getId(), buildAbsoluteOptionsForStock(stock));
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
     */
    private Map<Integer, List<CumulativeBbOption>> buildIncrementalOptions(List<TornStocksDO> allStocks,
                                                                           Map<Integer, Integer> currentLevels) {
        Map<Integer, List<CumulativeBbOption>> optionsMap = new HashMap<>();

        for (TornStocksDO stock : allStocks) {
            if (isInvalidStock(stock)) {
                continue;
            }

            List<CumulativeBbOption> absoluteOptions = buildAbsoluteOptionsForStock(stock);
            int currentLevel = currentLevels.getOrDefault(stock.getId(), 0);
            CumulativeBbOption baseOption = findOptionByLevel(absoluteOptions, currentLevel);

            long baseCost = baseOption.cumulativeCost();
            long baseProfit = baseOption.cumulativeProfit();

            List<CumulativeBbOption> incrementalOptions = absoluteOptions.stream()
                    .filter(option -> option.level() >= currentLevel)
                    .map(option -> new CumulativeBbOption(
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
     * 构建单支股票的绝对累计选项
     */
    private List<CumulativeBbOption> buildAbsoluteOptionsForStock(TornStocksDO stock) {
        List<CumulativeBbOption> options = new ArrayList<>();
        options.add(new CumulativeBbOption(stock.getId(), 0, 0, 0));
        long cumulativeCost = 0L;
        long cumulativeProfit = 0L;
        for (int level = 1; level <= MAX_BB_LEVEL; level++) {
            NextCumulativeValue nextValue = calculateNextCumulativeValue(stock, level, cumulativeCost, cumulativeProfit);
            if (!nextValue.valid()) {
                break;
            }
            cumulativeCost = nextValue.cumulativeCost();
            cumulativeProfit = nextValue.cumulativeProfit();
            options.add(new CumulativeBbOption(stock.getId(), level, cumulativeCost, cumulativeProfit));
        }
        return options;
    }

    /**
     * 计算下一个累计值
     */
    private NextCumulativeValue calculateNextCumulativeValue(TornStocksDO stock, int level,
                                                             long currentCumulativeCost, long currentCumulativeProfit) {
        long levelCost = calculateBbCost(stock.getBaseCost(), level);
        if (levelCost <= 0) {
            return NextCumulativeValue.invalid();
        }
        long nextCumulativeCost = safeAdd(currentCumulativeCost, levelCost);
        if (nextCumulativeCost < 0) {
            return NextCumulativeValue.invalid();
        }
        long nextCumulativeProfit = safeAdd(currentCumulativeProfit, stock.getYearProfit());
        if (nextCumulativeProfit < 0) {
            return NextCumulativeValue.invalid();
        }
        return NextCumulativeValue.valid(nextCumulativeCost, nextCumulativeProfit);
    }

    /**
     * 从选项中按等级查找目标项，找不到则返回0级
     */
    private CumulativeBbOption findOptionByLevel(List<CumulativeBbOption> options, int level) {
        return options.stream()
                .filter(option -> option.level() == level)
                .findFirst()
                .orElse(new CumulativeBbOption(options.isEmpty() ? 0 : options.getFirst().stockId(),
                        0, 0, 0));
    }

    /**
     * 分组背包DP求解
     *
     * <p>每支股票是一组，每组只能选一个最终等级
     */
    private DpResult solveGroupedKnapsack(Map<Integer, List<CumulativeBbOption>> stockOptions, int capacity) {
        long[] dp = new long[capacity + 1];

        @SuppressWarnings("unchecked")
        Map<Integer, Integer>[] solutions = new HashMap[capacity + 1];
        for (int i = 0; i <= capacity; i++) {
            solutions[i] = new HashMap<>();
        }

        for (Map.Entry<Integer, List<CumulativeBbOption>> entry : stockOptions.entrySet()) {
            processStockGroup(entry.getKey(), entry.getValue(), capacity, dp, solutions);
        }

        log.debug("DP求解完成，最大利润: {}", NumberUtils.formatCompactNumber(dp[capacity]));
        return new DpResult(solutions[capacity], dp[capacity]);
    }

    /**
     * 处理单个股票组的DP状态转移
     */
    private void processStockGroup(int stockId,
                                   List<CumulativeBbOption> options,
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
            for (CumulativeBbOption option : options) {
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
     * 根据最优等级结果构建最终投资组合
     */
    private Set<BBOpportunity> backtrackSolution(Map<Integer, Integer> optimalLevels, List<TornStocksDO> allStocks) {
        Set<BBOpportunity> portfolio = new HashSet<>();
        if (optimalLevels == null || optimalLevels.isEmpty()) {
            return portfolio;
        }

        for (TornStocksDO stock : allStocks) {
            if (isInvalidStock(stock)) {
                continue;
            }

            int maxLevel = optimalLevels.getOrDefault(stock.getId(), 0);
            for (int level = 1; level <= maxLevel; level++) {
                long cost = calculateBbCost(stock.getBaseCost(), level);
                long sharesRequired = calculateBbSharesRequired(stock.getBenefitReq(), level);
                if (cost <= 0 || sharesRequired <= 0) {
                    continue;
                }

                double roi = calculateRoi(stock.getYearProfit(), cost);
                portfolio.add(new BBOpportunity(stock.getId(), stock.getStocksShortname(), level, cost,
                        sharesRequired, stock.getYearProfit(), roi));
            }
        }
        return portfolio;
    }

    /**
     * 生成买卖操作建议
     *
     * <p>卖出按BB等级降序，买入按BB等级升序，保证操作顺序合理
     */
    private List<OptimalAction> generateActions(Set<BBOpportunity> current,
                                                Set<BBOpportunity> target,
                                                boolean buyOnly) {
        Set<String> currentIds = current.stream().map(BBOpportunity::uniqueId).collect(Collectors.toSet());
        Set<String> targetIds = target.stream().map(BBOpportunity::uniqueId).collect(Collectors.toSet());

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
     * 计算指数增长值
     *
     * <p>公式：baseValue * 2^(level - 1)
     */
    private long calculateExponentialValue(long baseValue, int level) {
        if (baseValue <= 0 || level < 1 || level > Long.SIZE - 1) {
            return -1L;
        }

        long multiplier = 1L << (level - 1);
        if (baseValue > Long.MAX_VALUE / multiplier) {
            return -1L;
        }
        return baseValue * multiplier;
    }

    /**
     * 计算第level个BB的成本
     */
    private long calculateBbCost(long baseCost, int level) {
        return calculateExponentialValue(baseCost, level);
    }

    /**
     * 计算第level个BB单独所需股数
     */
    private long calculateBbSharesRequired(long benefitReq, int level) {
        return calculateExponentialValue(benefitReq, level);
    }

    /**
     * 计算拥有第level个BB所需的累计股数
     *
     * <p>公式：benefitReq * (2^level - 1)
     *
     * <p>当前传入参数 sharesForThisBb = benefitReq * 2^(level-1)
     * 故累计股数 = sharesForThisBb * (2^level - 1) / 2^(level - 1)
     */
    private long calculateCumulativeShares(long sharesForThisBb, int level) {
        if (sharesForThisBb <= 0 || level < 1 || level > Long.SIZE - 1) {
            return -1L;
        }

        long denominator = 1L << (level - 1);
        long numeratorFactor = (1L << level) - 1L;

        if (sharesForThisBb > Long.MAX_VALUE / numeratorFactor) {
            return -1L;
        }

        return (sharesForThisBb * numeratorFactor) / denominator;
    }

    /**
     * 预算缩放：向下取整
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
     */
    private int scaleCost(long cost) {
        if (cost <= 0) {
            return 0;
        }
        long scaled = (cost + CAPITAL_SCALE_UNIT - 1) / CAPITAL_SCALE_UNIT;
        return scaled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    /**
     * 打印投资组合摘要
     */
    private void logPortfolioSummary(String title, Set<BBOpportunity> portfolio) {
        long totalProfit = portfolio.stream().mapToLong(BBOpportunity::yearProfit).sum();
        long totalCost = portfolio.stream().mapToLong(BBOpportunity::cost).sum();

        log.info("{}: {} 个BB, 总年利润: {}, 总成本: {}",
                title,
                portfolio.size(),
                NumberUtils.formatCompactNumber(totalProfit),
                NumberUtils.formatCompactNumber(totalCost));

        if (log.isDebugEnabled()) {
            portfolio.stream()
                    .sorted(Comparator.comparing(BBOpportunity::uniqueId))
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
     * 校验最终组合是否在真实预算内
     *
     * <p>普通模式：按组合总成本 <= 总资本判断
     * <p>只买不卖模式：按“目标组合总成本 - 当前组合总成本” <= 现金预算判断
     */
    private boolean isWithinBudget(Set<BBOpportunity> targetPortfolio,
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
     * 根据当前等级映射计算已持仓成本
     */
    private long calculatePortfolioCostByLevels(Map<Integer, Integer> levels, Set<BBOpportunity> targetPortfolio) {
        if (levels == null || levels.isEmpty()) {
            return 0L;
        }

        Map<Integer, Integer> levelMap = new HashMap<>(levels);
        long total = 0L;

        Map<Integer, List<BBOpportunity>> grouped = targetPortfolio.stream()
                .collect(Collectors.groupingBy(BBOpportunity::stockId));

        for (Map.Entry<Integer, Integer> entry : levelMap.entrySet()) {
            int stockId = entry.getKey();
            int maxLevel = entry.getValue();
            List<BBOpportunity> list = grouped.getOrDefault(stockId, Collections.emptyList());
            for (BBOpportunity opportunity : list) {
                if (opportunity.bbLevel() <= maxLevel) {
                    total = safeAdd(total, opportunity.cost());
                }
            }
        }

        return total;
    }

    /**
     * 计算组合总成本
     */
    private long sumCost(Set<BBOpportunity> portfolio) {
        return portfolio.stream().mapToLong(BBOpportunity::cost).sum();
    }

    /**
     * 计算ROI
     */
    private double calculateRoi(long yearProfit, long cost) {
        if (cost <= 0) {
            return 0D;
        }
        return (double) yearProfit / cost;
    }

    /**
     * long安全加法，溢出返回-1
     */
    private long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return -1L;
        }
        if (right < 0 && left < Long.MIN_VALUE - right) {
            return -1L;
        }
        return left + right;
    }

    /**
     * 校验股票基础数据是否合法
     */
    private boolean isInvalidStock(TornStocksDO stock) {
        return stock == null
                || stock.getId() == null
                || stock.getStocksShortname() == null
                || stock.getBaseCost() == null
                || stock.getBenefitReq() == null
                || stock.getYearProfit() == null
                || stock.getBaseCost() <= 0
                || stock.getBenefitReq() <= 0
                || stock.getYearProfit() < 0;
    }

    /**
     * 单个BB投资机会
     *
     * @param stockId                 股票ID
     * @param stockShortName          股票简称
     * @param bbLevel                 BB等级
     * @param cost                    该BB单级成本
     * @param sharesRequiredForThisBb 该BB单独需要股数（非累计）
     * @param yearProfit              该BB带来的年利润
     * @param roi                     投资回报率
     */
    private record BBOpportunity(int stockId,
                                 String stockShortName,
                                 int bbLevel,
                                 long cost,
                                 long sharesRequiredForThisBb,
                                 long yearProfit,
                                 double roi) {
        public String uniqueId() {
            return stockShortName + "-" + bbLevel;
        }
    }

    /**
     * 累计购买选项
     *
     * @param stockId          股票ID
     * @param level            最终等级
     * @param cumulativeCost   累计成本；在buyOnly模式下为相对当前持仓的增量成本
     * @param cumulativeProfit 累计利润；在buyOnly模式下为相对当前持仓的增量利润
     */
    private record CumulativeBbOption(int stockId,
                                      int level,
                                      long cumulativeCost,
                                      long cumulativeProfit) {
    }

    /**
     * 下一个累计值
     *
     * @param valid            是否合法
     * @param cumulativeCost   累计成本
     * @param cumulativeProfit 累计利润
     */
    private record NextCumulativeValue(boolean valid,
                                       long cumulativeCost,
                                       long cumulativeProfit) {
        private static NextCumulativeValue invalid() {
            return new NextCumulativeValue(false, 0L, 0L);
        }

        private static NextCumulativeValue valid(long cumulativeCost, long cumulativeProfit) {
            return new NextCumulativeValue(true, cumulativeCost, cumulativeProfit);
        }
    }

    /**
     * DP求解结果
     *
     * @param optimalLevels 每支股票最终应达到的最优等级
     * @param maxProfit     最大利润
     */
    private record DpResult(Map<Integer, Integer> optimalLevels,
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