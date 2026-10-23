package pn.torn.goldeneye.torn.manager.user.dividend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pn.torn.goldeneye.repository.model.torn.stocks.TornStocksDO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksDetailVO;
import pn.torn.goldeneye.torn.model.user.stocks.TornUserStocksVO;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 股票分红BB机会计算工厂
 *
 * <p>只做纯计算与数据结构转换：BB等级的成本/股数/ROI、当前持仓识别、
 * 以及由最优等级回溯出目标组合。不含任何持久化与事务逻辑。</p>
 *
 * @author Bai
 * @version 1.9.3
 * @since 2026.10.23
 */
@Component
@Slf4j
public class StockDividendOpportunityFactory {
    /**
     * BB等级上限，防止指数增长导致计算规模和金额失控
     */
    private static final int MAX_BB_LEVEL = 10;

    /**
     * 生成所有股票所有可能的单个BB机会
     *
     * @param allStocks 待选股票列表
     * @return BB投资机会列表
     */
    public List<BbOpportunity> buildAllOpportunities(List<TornStocksDO> allStocks) {
        List<BbOpportunity> opportunities = new ArrayList<>();
        for (TornStocksDO stock : allStocks) {
            if (isInvalidStock(stock)) {
                log.warn("股票数据无效，跳过，stockId={}, shortName={}",
                        stock == null ? null : stock.getId(),
                        stock == null ? null : stock.getStocksShortname());
                continue;
            }

            addStockOpportunities(opportunities, stock);
        }

        log.debug("生成 {} 个 BB 投资机会", opportunities.size());
        return opportunities;
    }

    /**
     * 根据用户持股数识别当前拥有的BB集合
     *
     * @param allOpportunities 全部BB机会
     * @param userStocks       用户当前持仓
     * @return 当前已拥有的BB集合
     */
    public Set<BbOpportunity> identifyCurrentPortfolio(List<BbOpportunity> allOpportunities,
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

        Set<BbOpportunity> owned = new HashSet<>();
        for (BbOpportunity opportunity : allOpportunities) {
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
     *
     * @param currentPortfolio 当前持有BB集合
     * @return 股票ID到最高BB等级的映射
     */
    public Map<Integer, Integer> extractMaxOwnedLevels(Set<BbOpportunity> currentPortfolio) {
        if (currentPortfolio == null || currentPortfolio.isEmpty()) {
            return Collections.emptyMap();
        }

        return currentPortfolio.stream().collect(Collectors.toMap(
                BbOpportunity::stockId,
                BbOpportunity::bbLevel,
                Math::max));
    }

    /**
     * 根据最优等级结果构建最终投资组合
     *
     * @param optimalLevels 每支股票最终应达到的最优等级
     * @param allStocks     待选股票列表
     * @return 目标投资组合
     */
    public Set<BbOpportunity> backtrack(Map<Integer, Integer> optimalLevels, List<TornStocksDO> allStocks) {
        Set<BbOpportunity> portfolio = new HashSet<>();
        if (optimalLevels == null || optimalLevels.isEmpty()) {
            return portfolio;
        }

        for (TornStocksDO stock : allStocks) {
            if (isInvalidStock(stock)) {
                continue;
            }

            int maxLevel = optimalLevels.getOrDefault(stock.getId(), 0);
            for (int level = 1; level <= maxLevel; level++) {
                long cost = calculateCost(stock.getBaseCost(), level);
                long sharesRequired = calculateShares(stock.getBenefitReq(), level);
                if (cost <= 0 || sharesRequired <= 0) {
                    continue;
                }

                portfolio.add(new BbOpportunity(stock.getId(), stock.getStocksShortname(), level, cost,
                        sharesRequired, stock.getYearProfit(), calculateRoi(stock.getYearProfit(), cost)));
            }
        }
        return portfolio;
    }

    /**
     * 构建单支股票的绝对累计选项
     *
     * <p>例如：
     * <ul>
     *     <li>0BB -> 成本0，利润0</li>
     *     <li>1BB -> 累计成本500m，累计利润100m</li>
     *     <li>2BB -> 累计成本1.5b，累计利润200m</li>
     * </ul>
     *
     * @param stock 股票
     * @return 该股票从0级到最高可计算等级的累计选项
     */
    public List<CumulativeOption> buildAbsoluteOptions(TornStocksDO stock) {
        List<CumulativeOption> options = new ArrayList<>();
        options.add(new CumulativeOption(stock.getId(), 0, 0, 0));
        long cumulativeCost = 0L;
        long cumulativeProfit = 0L;
        for (int level = 1; level <= MAX_BB_LEVEL; level++) {
            NextCumulativeValue nextValue = calculateNextCumulativeValue(stock, level, cumulativeCost, cumulativeProfit);
            if (!nextValue.valid()) {
                break;
            }
            cumulativeCost = nextValue.cumulativeCost();
            cumulativeProfit = nextValue.cumulativeProfit();
            options.add(new CumulativeOption(stock.getId(), level, cumulativeCost, cumulativeProfit));
        }
        return options;
    }

    /**
     * 计算第level个BB的成本
     *
     * @param baseCost 基础成本
     * @param level    BB等级
     * @return 该等级成本; 非法或溢出时返回-1
     */
    public long calculateCost(long baseCost, int level) {
        return calculateExponentialValue(baseCost, level);
    }

    /**
     * 计算第level个BB单独所需股数
     *
     * @param benefitReq 分红所需股数
     * @param level      BB等级
     * @return 该等级所需股数; 非法或溢出时返回-1
     */
    public long calculateShares(long benefitReq, int level) {
        return calculateExponentialValue(benefitReq, level);
    }

    /**
     * 计算年化回报率
     *
     * @param yearProfit 年利润
     * @param cost       成本
     * @return 回报率; 成本非法时返回0
     */
    public double calculateRoi(long yearProfit, long cost) {
        if (cost <= 0) {
            return 0D;
        }
        return (double) yearProfit / cost;
    }

    /**
     * long安全加法，溢出返回-1
     *
     * @param left  左值
     * @param right 右值
     * @return 相加结果; 溢出时返回-1
     */
    public long safeAdd(long left, long right) {
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
     *
     * @param stock 股票
     * @return true为数据无效
     */
    public boolean isInvalidStock(TornStocksDO stock) {
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
     * 追加单支股票所有可计算的BB机会
     *
     * @param opportunities 机会列表
     * @param stock         股票
     */
    private void addStockOpportunities(List<BbOpportunity> opportunities, TornStocksDO stock) {
        for (int level = 1; level <= MAX_BB_LEVEL; level++) {
            long cost = calculateCost(stock.getBaseCost(), level);
            long sharesRequired = calculateShares(stock.getBenefitReq(), level);
            if (cost <= 0 || sharesRequired <= 0) {
                log.warn("股票 {} 的第 {} BB 计算溢出或非法，停止生成更高等级",
                        stock.getStocksShortname(), level);
                break;
            }

            opportunities.add(new BbOpportunity(stock.getId(), stock.getStocksShortname(), level, cost,
                    sharesRequired, stock.getYearProfit(), calculateRoi(stock.getYearProfit(), cost)));
        }
    }

    /**
     * 计算下一个累计值
     *
     * @param stock                   股票
     * @param level                   BB等级
     * @param currentCumulativeCost   当前累计成本
     * @param currentCumulativeProfit 当前累计利润
     * @return 下一个累计值; 溢出或非法时返回invalid
     */
    private NextCumulativeValue calculateNextCumulativeValue(TornStocksDO stock, int level,
                                                             long currentCumulativeCost, long currentCumulativeProfit) {
        long levelCost = calculateCost(stock.getBaseCost(), level);
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
     * 计算指数增长值
     *
     * <p>公式：baseValue * 2^(level - 1)
     *
     * @param baseValue 基础值
     * @param level     BB等级
     * @return 增长后的值; 非法或溢出时返回-1
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
     * 计算拥有第level个BB所需的累计股数
     *
     * <p>公式：benefitReq * (2^level - 1)
     *
     * <p>当前传入参数 sharesForThisBb = benefitReq * 2^(level-1)
     * 故累计股数 = sharesForThisBb * (2^level - 1) / 2^(level - 1)
     *
     * @param sharesForThisBb 该BB单独需要股数(非累计)
     * @param level           BB等级
     * @return 累计所需股数; 非法或溢出时返回-1
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
    public record BbOpportunity(
            int stockId,
            String stockShortName,
            int bbLevel,
            long cost,
            long sharesRequiredForThisBb,
            long yearProfit,
            double roi) {
        /**
         * 唯一标识
         *
         * @return 股票简称与BB等级拼接的唯一键
         */
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
    public record CumulativeOption(
            int stockId,
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
    private record NextCumulativeValue(
            boolean valid,
            long cumulativeCost,
            long cumulativeProfit) {
        /**
         * 非法累计值
         *
         * @return 非法累计值
         */
        private static NextCumulativeValue invalid() {
            return new NextCumulativeValue(false, 0L, 0L);
        }

        /**
         * 合法累计值
         *
         * @param cumulativeCost   累计成本
         * @param cumulativeProfit 累计利润
         * @return 合法累计值
         */
        private static NextCumulativeValue valid(long cumulativeCost, long cumulativeProfit) {
            return new NextCumulativeValue(true, cumulativeCost, cumulativeProfit);
        }
    }
}
