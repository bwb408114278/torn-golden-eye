package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBatchExitService;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayEquityPoint;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayTrackEnum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 回放轨道指标累加器。
 *
 * <p>承载正式轨道的已实现净收益、消息计数、回撤与槽位利用率,并按轮次追加净值点
 * (正式与影子两条曲线)。净值点与指标均为内存累加,全部写出到产物、不写业务表。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
final class StockReplayMetrics {

    /**
     * 卖出费率(0.1%手续费)。
     */
    private static final BigDecimal SELL_FEE_RATE = StockBatchExitService.SELL_FEE_RATE;
    /**
     * 统计精度。
     */
    private static final int STAT_SCALE = 10;

    private final StockReplayContext context;
    private final StockReplayPortfolio portfolio;
    private final StockReplayTrackEnum track;
    private final String runId;
    private final Map<String, List<StockReplayEquityPoint>> equityByTrack;
    private final boolean collectShadow;

    private BigDecimal realizedReturn = BigDecimal.ZERO;
    private long messageCount = 0;
    private BigDecimal drawdownPeak = null;
    private BigDecimal maxDrawdown = BigDecimal.ZERO;
    private BigDecimal utilizationSum = BigDecimal.ZERO;
    private long utilizationCount = 0;

    /**
     * 构造指标累加器。
     *
     * @param context       回放上下文
     * @param portfolio     轨道组合
     * @param track         正式轨道
     * @param runId         回放运行标识
     * @param equityByTrack 净值点输出映射(共享,直接追加)
     * @param collectShadow 是否同时记录影子净值
     */
    StockReplayMetrics(StockReplayContext context, StockReplayPortfolio portfolio,
                       StockReplayTrackEnum track, String runId,
                       Map<String, List<StockReplayEquityPoint>> equityByTrack,
                       boolean collectShadow) {
        this.context = context;
        this.portfolio = portfolio;
        this.track = track;
        this.runId = runId;
        this.equityByTrack = equityByTrack;
        this.collectShadow = collectShadow;
    }

    /**
     * 记录一笔正式买入的消息计数。
     */
    void onFormalBuy() {
        messageCount++;
    }

    /**
     * 记录一笔正式卖出的消息计数与已实现净收益。
     *
     * @param batch 已结算卖出批次
     */
    void onFormalSell(TornStockVirtualBatchDO batch) {
        messageCount++;
        if (batch.getSellProceeds() != null && batch.getInvestedCash() != null) {
            realizedReturn = realizedReturn.add(batch.getSellProceeds())
                    .subtract(batch.getInvestedCash());
        }
    }

    /**
     * 追加本轮正式净值点并更新回撤与利用率指标。
     *
     * @param t           本轮时间
     * @param barByStock  本轮bar(按股票ID索引)
     */
    void recordEquityPoint(LocalDateTime t, Map<Integer, TornStockMarketBar15mDO> barByStock) {
        BigDecimal cashAndReserved =
                context.portfolioService().calculateCashAndReserved(portfolio.slots());
        BigDecimal utilization = utilization(occupiedSlots());
        if (utilization != null) {
            utilizationSum = utilizationSum.add(utilization);
            utilizationCount++;
        }
        MarketValue open = aggregateOpen(portfolio.activeBatches(), barByStock);
        BigDecimal equity = open.missingPrice ? null : cashAndReserved.add(open.marketValue);
        equityByTrack.get(track.getCode()).add(new StockReplayEquityPoint(
                runId, track.getCode(), t, equity, cashAndReserved,
                open.openPositions, realizedReturn, utilization));
        if (equity != null) {
            updateDrawdown(equity);
        }
        if (collectShadow) {
            recordShadowEquityPoint(t, barByStock);
        }
    }

    /**
     * 追加本轮影子净值点(恒1股无现金口径)。
     *
     * @param t           本轮时间
     * @param barByStock  本轮bar(按股票ID索引)
     */
    private void recordShadowEquityPoint(LocalDateTime t,
                                         Map<Integer, TornStockMarketBar15mDO> barByStock) {
        MarketValue open = aggregateShadow(portfolio.activeBatches(), barByStock);
        String code = StockReplayTrackEnum.UNLIMITED_SHADOW.getCode();
        equityByTrack.get(code).add(new StockReplayEquityPoint(
                runId, code, t,
                open.missingPrice ? null : open.marketValue, BigDecimal.ZERO,
                open.openPositions, null, null));
    }

    /**
     * 消息计数。
     *
     * @return 正式买入卖出消息合计
     */
    long messageCount() {
        return messageCount;
    }

    /**
     * 最大回撤。
     *
     * @return 最大回撤
     */
    BigDecimal maxDrawdown() {
        return maxDrawdown;
    }

    /**
     * 平均槽位占用率。
     *
     * @return 平均占用率;无净值点时返回null
     */
    BigDecimal averageUtilization() {
        return utilizationCount == 0 ? null
                : utilizationSum.divide(BigDecimal.valueOf(utilizationCount),
                        STAT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal utilization(int occupiedSlots) {
        return track.getSlotCount() <= 0 ? null
                : BigDecimal.valueOf(occupiedSlots)
                        .divide(BigDecimal.valueOf(track.getSlotCount()), STAT_SCALE, RoundingMode.HALF_UP);
    }

    private int occupiedSlots() {
        return (int) portfolio.slots().stream()
                .filter(slot -> !StockSlotStatusEnum.AVAILABLE.getCode().equals(slot.getSlotStatus()))
                .count();
    }

    private void updateDrawdown(BigDecimal equity) {
        if (drawdownPeak == null || equity.compareTo(drawdownPeak) > 0) {
            drawdownPeak = equity;
        }
        if (drawdownPeak.signum() > 0) {
            BigDecimal drawdown = equity.divide(drawdownPeak, STAT_SCALE, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);
            if (drawdown.compareTo(maxDrawdown) < 0) {
                maxDrawdown = drawdown;
            }
        }
    }

    private MarketValue aggregateOpen(List<TornStockVirtualBatchDO> batches,
                                      Map<Integer, TornStockMarketBar15mDO> barByStock) {
        MarketValue value = new MarketValue();
        for (TornStockVirtualBatchDO batch : batches) {
            if (!isOpenStatus(batch.getBatchStatus())) {
                continue;
            }
            value.accept(openPrice(batch, barByStock), quantity(batch));
        }
        return value;
    }

    private MarketValue aggregateShadow(List<TornStockVirtualBatchDO> batches,
                                        Map<Integer, TornStockMarketBar15mDO> barByStock) {
        MarketValue value = new MarketValue();
        for (TornStockVirtualBatchDO batch : batches) {
            if (!isShadowOpen(batch)) {
                continue;
            }
            value.accept(openPrice(batch, barByStock), 1L);
        }
        return value;
    }

    private static boolean isShadowOpen(TornStockVirtualBatchDO batch) {
        return StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode().equals(batch.getLedgerType())
                && isOpenStatus(batch.getBatchStatus());
    }

    private static long quantity(TornStockVirtualBatchDO batch) {
        return batch.getQuantity() == null ? 0L : batch.getQuantity();
    }

    private static BigDecimal openPrice(TornStockVirtualBatchDO batch,
                                        Map<Integer, TornStockMarketBar15mDO> barByStock) {
        TornStockMarketBar15mDO bar = barByStock.get(batch.getStocksId());
        if (bar == null || !Stock15mBarBuildService.isUsable(bar) || bar.getLastPrice() == null) {
            return null;
        }
        return bar.getLastPrice();
    }

    private static boolean isOpenStatus(String status) {
        return StockBatchStatusEnum.OPEN.getCode().equals(status)
                || StockBatchStatusEnum.DATA_STALE.getCode().equals(status)
                || StockBatchStatusEnum.EXIT_PENDING.getCode().equals(status)
                || StockBatchStatusEnum.DATA_STALE_EXIT.getCode().equals(status);
    }

    /**
     * 持仓市值聚合(含缺失价格标记)。
     */
    private static final class MarketValue {
        private BigDecimal marketValue = BigDecimal.ZERO;
        private int openPositions;
        private boolean missingPrice;

        private void accept(BigDecimal price, long quantity) {
            if (price == null) {
                missingPrice = true;
                return;
            }
            openPositions++;
            marketValue = marketValue.add(
                    BigDecimal.valueOf(quantity).multiply(price).multiply(SELL_FEE_RATE));
        }
    }
}
