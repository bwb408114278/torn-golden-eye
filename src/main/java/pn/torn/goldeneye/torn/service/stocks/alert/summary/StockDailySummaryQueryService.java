package pn.torn.goldeneye.torn.service.stocks.alert.summary;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.PortfolioEquityCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.PortfolioEquityCalculator.EquityResult;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.DailySummaryData;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.OpenPosition;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.PortfolioSummary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 股票日报查询服务 - 一次读取α正式组合、存量正式组合与α影子组合的只读数据并组装只读DTO
 * <p>
 * 本类只负责DAO读取与数据组装,计算部分委托给纯计算组件:
 * <ul>
 *   <li>{@link PortfolioEquityCalculator} - 各组合权益与缺失行情判定</li>
 *   <li>{@link DailySummaryMetricsCalculator} - 昨日买卖、已实现净收益金额与投入成本</li>
 * </ul>
 * 三段组合字段口径一致,<b>互不合计</b>:α正式组合与α影子组合按各自组合编码读取活跃批次与动作批次,
 * 存量正式组合固定读取 {@code VIP_FORMAL}。"昨日动作"批次按entryTime/exitTime落在摘要日内判定,
 * "当前活跃仓"按批次状态判定,两者时间基准不同,不得混淆。
 * <p>
 * 三段组合的开放仓位股票ID合并为一次 {@code selectLatestUsableByStocks} 批量查询,避免按持仓N+1。
 *
 * @author Bai
 * @version 1.6.5
 * @since 2026.08.09
 */
@Service
@RequiredArgsConstructor
public class StockDailySummaryQueryService {

    private final TornStockPortfolioSlotDAO portfolioSlotDAO;
    private final TornStockVirtualBatchDAO virtualBatchDAO;
    private final TornStockMarketBar15mDAO bar15mDAO;
    private final StockMarketClock marketClock;
    private final PortfolioEquityCalculator equityCalculator;
    private final DailySummaryMetricsCalculator metricsCalculator;

    /**
     * 构建每日摘要数据,包含α正式组合、存量正式组合与α影子组合三段。
     *
     * @param summaryDate 摘要日期(发送日前一自然日)
     * @return 摘要数据对象
     */
    public DailySummaryData buildSummaryData(LocalDate summaryDate) {
        LocalDateTime generatedAt = marketClock.now();
        LocalDateTime dayStart = summaryDate.atStartOfDay();
        LocalDateTime dayEnd = summaryDate.plusDays(1).atStartOfDay();

        List<TornStockVirtualBatchDO> alphaBatches =
                virtualBatchDAO.selectActiveAlphaBatches(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE);
        List<TornStockVirtualBatchDO> legacyBatches = virtualBatchDAO.selectActiveFormalBatches();
        List<TornStockVirtualBatchDO> alphaShadowBatches =
                virtualBatchDAO.selectActiveAlphaBatches(StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE);

        SummaryContext context = new SummaryContext(dayStart, dayEnd, generatedAt,
                loadLatestBars(List.of(alphaBatches, legacyBatches, alphaShadowBatches), generatedAt));

        return new DailySummaryData(summaryDate,
                buildPortfolio(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE,
                        StockPortfolioService.VIP_ALPHA_SLOT_COUNT, context, alphaBatches,
                        virtualBatchDAO.selectAlphaActionBatches(
                                StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, dayStart, dayEnd)),
                buildPortfolio(StockPortfolioService.PORTFOLIO_CODE,
                        StockPortfolioService.SLOT_COUNT, context, legacyBatches,
                        virtualBatchDAO.selectFormalActionBatches(dayStart, dayEnd)),
                buildPortfolio(StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE,
                        StockPortfolioService.VIP_ALPHA_SHADOW_SLOT_COUNT, context, alphaShadowBatches,
                        virtualBatchDAO.selectAlphaActionBatches(
                                StockPortfolioService.VIP_ALPHA_SHADOW_PORTFOLIO_CODE, dayStart, dayEnd)));
    }

    /**
     * 构建单个组合的摘要数据。
     *
     * @param portfolioCode 组合编码
     * @param slotCount     该组合的槽位总数
     * @param context       本轮共享只读上下文
     * @param activeBatches 该组合的活跃批次
     * @param actionBatches 该组合昨日有入场或出场动作的批次
     * @return 组合摘要
     */
    private PortfolioSummary buildPortfolio(String portfolioCode, int slotCount, SummaryContext context,
                                            List<TornStockVirtualBatchDO> activeBatches,
                                            List<TornStockVirtualBatchDO> actionBatches) {
        List<TornStockPortfolioSlotDO> slots = portfolioSlotDAO.selectAllByPortfolioCode(portfolioCode);
        EquityResult equity = equityCalculator.calculateEquity(
                slots, activeBatches, context.latestBarByStock(), context.generatedAt());
        return new PortfolioSummary(portfolioCode, slotCount,
                metricsCalculator.countOccupiedSlots(slots),
                equity.equity(), equity.cashAndReserved(), equity.missingPriceStocks(), equity.priceAsOf(),
                metricsCalculator.countBatchesInRange(
                        actionBatches, context.dayStart(), context.dayEnd(), true),
                metricsCalculator.countBatchesInRange(
                        actionBatches, context.dayStart(), context.dayEnd(), false),
                metricsCalculator.sumRealizedProfit(actionBatches, context.dayStart(), context.dayEnd()),
                metricsCalculator.sumInvestedCash(actionBatches, context.dayStart(), context.dayEnd()),
                extractOpenPositions(activeBatches),
                metricsCalculator.countStaleBatches(activeBatches));
    }

    /**
     * 提取开放仓位的展示事实(股票简称与入场参考价)。
     *
     * @param activeBatches 该组合的活跃批次
     * @return 开放仓位列表;无开放仓位时为空列表
     */
    private List<OpenPosition> extractOpenPositions(List<TornStockVirtualBatchDO> activeBatches) {
        return equityCalculator.extractOpenPositionBatches(activeBatches).stream()
                .filter(batch -> batch.getStocksShortname() != null)
                .map(batch -> new OpenPosition(batch.getStocksShortname(), batch.getEntryReferencePrice()))
                .toList();
    }

    /**
     * 批量加载最新且处于新鲜度窗口内的bar,按股票ID索引避免N+1查询。
     * <p>
     * 三段组合的开放仓位股票ID合并为一次查询,保证每个摘要周期最多一次行情批量读取。
     *
     * @param batchGroups 三段组合的活跃批次
     * @param generatedAt 日报生成时点
     * @return 按股票ID索引的最新bar映射
     */
    private Map<Integer, TornStockMarketBar15mDO> loadLatestBars(List<List<TornStockVirtualBatchDO>> batchGroups,
                                                                 LocalDateTime generatedAt) {
        List<Integer> stocksIds = batchGroups.stream()
                .flatMap(List::stream)
                .map(TornStockVirtualBatchDO::getStocksId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (stocksIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime latestAllowedBarStart = marketClock.currentEndedBucket()
                .minusMinutes(Stock15mBarBuildService.BUCKET_MINUTES);
        LocalDateTime minBarEndTime = generatedAt.minusMinutes(PortfolioEquityCalculator.MAX_PRICE_AGE_MINUTES);
        List<TornStockMarketBar15mDO> bars = bar15mDAO.selectLatestUsableByStocks(
                stocksIds, latestAllowedBarStart, minBarEndTime, Stock15mBarBuildService.BUILD_VERSION);
        Map<Integer, TornStockMarketBar15mDO> barByStock = new HashMap<>();
        if (CollectionUtils.isEmpty(bars)) {
            return barByStock;
        }
        for (TornStockMarketBar15mDO bar : bars) {
            if (Boolean.TRUE.equals(bar.getUsable()) && bar.getStocksId() != null) {
                barByStock.put(bar.getStocksId(), bar);
            }
        }
        return barByStock;
    }

    /**
     * 单次摘要构建的共享只读上下文。
     *
     * @param dayStart         摘要日期起始(含)
     * @param dayEnd           摘要日期结束(不含)
     * @param generatedAt      日报生成时点
     * @param latestBarByStock 按股票ID索引的最新可用bar
     */
    private record SummaryContext(
            LocalDateTime dayStart,
            LocalDateTime dayEnd,
            LocalDateTime generatedAt,
            Map<Integer, TornStockMarketBar15mDO> latestBarByStock) {
    }
}
