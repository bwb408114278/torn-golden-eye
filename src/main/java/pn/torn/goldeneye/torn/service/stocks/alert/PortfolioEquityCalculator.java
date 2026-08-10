package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 组合权益计算器 - 纯计算正式/候选影子组合的现金、持仓市值、行情新鲜度与缺失价格
 * <p>
 * 本类只消费输入并产出权益结果,不访问DAO、不触发查询、不读取时钟,是查询服务的纯函数组件。
 * 开放仓位市值 = quantity × lastPrice × 0.999(扣除0.1%卖出手续费),currentPrice取生成时点前
 * {@value #MAX_PRICE_AGE_MINUTES} 分钟内、最近完整桶的bar.lastPrice。行情由调用方一次性批量加载后
 * 按股票ID索引传入。无开放仓位时权益等于现金+预留;任一开放仓位缺bar、价格非法或行情过期时权益
 * 按数据不足降级(equity=null)并按股票ID升序返回缺失行情股票简称,绝不回退到投入资金近似。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@Component
@RequiredArgsConstructor
public class PortfolioEquityCalculator {

    /**
     * 日报完整权益允许的最大行情滞后分钟数。
     */
    public static final long MAX_PRICE_AGE_MINUTES = 30L;

    private final StockPortfolioService portfolioService;

    /**
     * 计算组合权益(现金+预留+开放仓位当前市值)。
     *
     * @param slots            全部槽位
     * @param activeBatches    活跃批次(正式或候选影子账本)
     * @param latestBarByStock 按股票ID索引的最新可用bar
     * @param generatedAt      日报生成时点
     * @return 权益计算结果
     */
    public EquityResult calculateEquity(List<TornStockPortfolioSlotDO> slots,
                                        List<TornStockVirtualBatchDO> activeBatches,
                                        Map<Integer, TornStockMarketBar15mDO> latestBarByStock,
                                        LocalDateTime generatedAt) {
        BigDecimal cashAndReserved = portfolioService.calculateCashAndReserved(slots);
        List<TornStockVirtualBatchDO> openPositionBatches = extractOpenPositionBatches(activeBatches);
        if (openPositionBatches.isEmpty()) {
            return new EquityResult(cashAndReserved, cashAndReserved, List.of(), null);
        }

        List<String> missingPriceStocks = collectMissingPriceStocks(
                openPositionBatches, latestBarByStock, generatedAt);
        if (!missingPriceStocks.isEmpty()) {
            return new EquityResult(null, cashAndReserved, missingPriceStocks, null);
        }
        Map<Long, BigDecimal> batchMarketValues = new HashMap<>();
        for (TornStockVirtualBatchDO batch : openPositionBatches) {
            batchMarketValues.put(batch.getSlotId(), calculateBatchMarketValue(batch, latestBarByStock));
        }
        return new EquityResult(portfolioService.calculateEquity(slots, batchMarketValues), cashAndReserved,
                List.of(), findEarliestPriceAsOf(latestBarByStock));
    }

    /**
     * 提取需要当前行情估值的开放仓位。
     *
     * @param activeBatches 活跃批次
     * @return 具备槽位和持仓数量的开放仓位
     */
    public List<TornStockVirtualBatchDO> extractOpenPositionBatches(List<TornStockVirtualBatchDO> activeBatches) {
        if (CollectionUtils.isEmpty(activeBatches)) {
            return List.of();
        }
        return activeBatches.stream()
                .filter(batch -> batch.getSlotId() != null && batch.getQuantity() != null)
                .filter(batch -> StockBatchStatusEnum.OPEN.getCode().equals(batch.getBatchStatus())
                        || StockBatchStatusEnum.DATA_STALE.getCode().equals(batch.getBatchStatus())
                        || StockBatchStatusEnum.EXIT_PENDING.getCode().equals(batch.getBatchStatus())
                        || StockBatchStatusEnum.DATA_STALE_EXIT.getCode().equals(batch.getBatchStatus()))
                .toList();
    }

    /**
     * 收集缺失有效行情的股票简称，并按股票ID保证展示顺序确定。
     *
     * @param openPositionBatches 开放仓位
     * @param latestBarByStock    每股最新可用bar
     * @param summaryGeneratedAt  日报生成时点
     * @return 缺失行情股票简称
     */
    private List<String> collectMissingPriceStocks(List<TornStockVirtualBatchDO> openPositionBatches,
                                                   Map<Integer, TornStockMarketBar15mDO> latestBarByStock,
                                                   LocalDateTime summaryGeneratedAt) {
        return openPositionBatches.stream()
                .filter(batch -> calculateBatchMarketValue(batch, latestBarByStock) == null
                        || !isFreshPrice(latestBarByStock.get(batch.getStocksId()), summaryGeneratedAt))
                .sorted(Comparator.comparing(TornStockVirtualBatchDO::getStocksId))
                .map(TornStockVirtualBatchDO::getStocksShortname)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 校验参与日报估值的行情是否处于允许的新鲜度窗口。
     *
     * @param bar                实际选中的行情bar
     * @param summaryGeneratedAt 日报生成时点
     * @return bar结束时点位于生成时点前30分钟内时返回true
     */
    private boolean isFreshPrice(TornStockMarketBar15mDO bar, LocalDateTime summaryGeneratedAt) {
        if (bar == null || bar.getBarEndTime() == null) {
            return false;
        }
        LocalDateTime minBarEndTime = summaryGeneratedAt.minusMinutes(MAX_PRICE_AGE_MINUTES);
        return !bar.getBarEndTime().isBefore(minBarEndTime) && !bar.getBarEndTime().isAfter(summaryGeneratedAt);
    }

    /**
     * 获取完整权益中所有实际估值行情的最早结束时点。
     *
     * @param latestBarByStock 每股实际参与估值的行情
     * @return 最早行情结束时点
     */
    private LocalDateTime findEarliestPriceAsOf(Map<Integer, TornStockMarketBar15mDO> latestBarByStock) {
        return latestBarByStock.values().stream()
                .map(TornStockMarketBar15mDO::getBarEndTime)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    /**
     * 计算单个批次的当前市值。
     * <p>
     * marketValue = quantity × currentPrice × 0.999(扣除0.1%卖出手续费)。
     * 无最新bar或价格非法时返回null,由上层按数据不足处理,不回退到投入资金近似。
     *
     * @param batch            活跃批次
     * @param latestBarByStock 按股票ID索引的最新bar映射
     * @return 批次当前市值;行情缺失或不可用时返回null
     */
    private BigDecimal calculateBatchMarketValue(TornStockVirtualBatchDO batch,
                                                 Map<Integer, TornStockMarketBar15mDO> latestBarByStock) {
        TornStockMarketBar15mDO bar = latestBarByStock.get(batch.getStocksId());
        if (bar != null && Boolean.TRUE.equals(bar.getUsable())
                && bar.getLastPrice() != null && bar.getLastPrice().signum() > 0) {
            return bar.getLastPrice()
                    .multiply(BigDecimal.valueOf(batch.getQuantity()))
                    .multiply(StockPortfolioService.SELL_FEE_RATE);
        }
        return null;
    }

    /**
     * 组合权益计算结果。
     *
     * @param equity             完整组合权益；任一开放仓位缺行情时为null
     * @param cashAndReserved    可用现金与预留资金，不代表完整权益
     * @param missingPriceStocks 缺失有效行情的股票简称，按股票ID升序
     * @param priceAsOf          完整权益实际使用行情中的最早结束时点；行情不足时为null
     */
    public record EquityResult(
            BigDecimal equity,
            BigDecimal cashAndReserved,
            List<String> missingPriceStocks,
            LocalDateTime priceAsOf) {
    }
}
