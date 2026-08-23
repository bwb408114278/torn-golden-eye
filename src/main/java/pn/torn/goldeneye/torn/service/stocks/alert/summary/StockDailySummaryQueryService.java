package pn.torn.goldeneye.torn.service.stocks.alert.summary;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockBatchMarkDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketBar15mDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalEventDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockBatchMarkDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.PortfolioEquityCalculator.EquityResult;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.CandidateShadowSummary;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.DailySummaryData;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.FormalSummary;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService.ShadowSummary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.PortfolioEquityCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;

/**
 * 股票日报查询服务 - 一次读取正式/候选影子/影子研究的只读数据并组装只读DTO
 * <p>
 * 本类只负责DAO读取与数据组装,计算部分委托给纯计算组件:
 * <ul>
 *   <li>{@link PortfolioEquityCalculator} - 正式/候选影子组合权益</li>
 *   <li>{@link DynamicSellResearchSummaryCalculator} - 动态SELL研究mark统计</li>
 *   <li>{@link DailySummaryMetricsCalculator} - 买卖、风险、拒绝等统计</li>
 * </ul>
 * 正式与候选影子的开放仓位股票ID合并为一次 {@code selectLatestUsableByStocks} 批量查询,
 * 避免按持仓N+1;候选影子活跃/动作批次使用固定mapper SQL,不在Java中散落OR条件。
 * "昨日动作"批次按entryTime/exitTime落在摘要日内判定,"当前活跃仓"按批次状态判定,
 * 两者时间基准不同,不得混淆。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.09
 */
@Service
@RequiredArgsConstructor
public class StockDailySummaryQueryService {

    private final TornStockPortfolioSlotDAO portfolioSlotDAO;
    private final TornStockVirtualBatchDAO virtualBatchDAO;
    private final TornStockSignalEventDAO signalEventDAO;
    private final TornStockBatchMarkDAO batchMarkDAO;
    private final TornStockMarketBar15mDAO bar15mDAO;
    private final StockMarketClock marketClock;
    private final PortfolioEquityCalculator equityCalculator;
    private final DynamicSellResearchSummaryCalculator dynamicSellResearchCalculator;
    private final DailySummaryMetricsCalculator metricsCalculator;

    /**
     * 构建每日摘要数据,包含正式组合、候选影子组合与影子研究三部分。
     * <p>
     * 正式组合部分:查询VIP组合全部槽位与活跃正式批次,统计占用槽位、组合权益、
     * 昨日买入/卖出批次、昨日已实现净收益、开放批次股票列表与数据陈旧批次数量。
     * <br>候选影子部分:查询独立5槽账本槽位与活跃候选影子批次,单独统计占用槽位、
     * 权益、昨日买卖与净收益,不与正式或无限资金影子合计。
     * <br>影子研究部分:查询摘要日期范围内的信号事件,按portfolioDecision与rejectReason分组统计;
     * 查询昨日影子批次统计高风险观察数量,并按 {@code torn_stock_batch_mark} 统计动态SELL研究状态。
     *
     * @param summaryDate 摘要日期(发送日前一自然日)
     * @return 摘要数据对象
     */
    public DailySummaryData buildSummaryData(LocalDate summaryDate) {
        List<TornStockPortfolioSlotDO> formalSlots =
                portfolioSlotDAO.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE);
        List<TornStockVirtualBatchDO> formalActiveBatches = virtualBatchDAO.selectActiveFormalBatches();

        List<TornStockPortfolioSlotDO> candidateSlots = portfolioSlotDAO.selectAllByPortfolioCode(
                StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE);
        List<TornStockVirtualBatchDO> candidateActiveBatches =
                virtualBatchDAO.selectActiveCandidateShadowBatches();

        LocalDateTime generatedAt = marketClock.now();
        List<TornStockVirtualBatchDO> formalOpenBatches =
                equityCalculator.extractOpenPositionBatches(formalActiveBatches);
        List<TornStockVirtualBatchDO> candidateOpenBatches =
                equityCalculator.extractOpenPositionBatches(candidateActiveBatches);
        Map<Integer, TornStockMarketBar15mDO> latestBarByStock =
                loadLatestBars(formalOpenBatches, candidateOpenBatches, generatedAt);

        EquityResult formalEquity = equityCalculator.calculateEquity(
                formalSlots, formalActiveBatches, latestBarByStock, generatedAt);
        EquityResult candidateEquity = equityCalculator.calculateEquity(
                candidateSlots, candidateActiveBatches, latestBarByStock, generatedAt);

        LocalDateTime dayStart = summaryDate.atStartOfDay();
        LocalDateTime dayEnd = summaryDate.plusDays(1).atStartOfDay();

        List<TornStockVirtualBatchDO> yesterdayFormalBatches =
                dedupById(virtualBatchDAO.selectFormalActionBatches(dayStart, dayEnd));
        int formalBuyCount = metricsCalculator.countBatchesInRange(
                yesterdayFormalBatches, dayStart, dayEnd, true);
        int formalSellCount = metricsCalculator.countBatchesInRange(
                yesterdayFormalBatches, dayStart, dayEnd, false);
        BigDecimal formalNetReturn = metricsCalculator.sumNetReturn(yesterdayFormalBatches, dayStart, dayEnd);
        List<String> formalOpenStocks = metricsCalculator.extractOpenBatchStocks(formalActiveBatches);
        int staleBatchCount = metricsCalculator.countStaleBatches(formalActiveBatches);

        FormalSummary formal = new FormalSummary(metricsCalculator.countOccupiedSlots(formalSlots),
                formalEquity.equity(), formalEquity.cashAndReserved(), formalEquity.missingPriceStocks(),
                formalEquity.priceAsOf(), formalBuyCount, formalSellCount, formalNetReturn,
                formalOpenStocks, staleBatchCount, summaryDate);

        List<TornStockVirtualBatchDO> yesterdayCandidateBatches =
                dedupById(virtualBatchDAO.selectCandidateShadowActionBatches(dayStart, dayEnd));
        int candidateBuyCount = metricsCalculator.countBatchesInRange(
                yesterdayCandidateBatches, dayStart, dayEnd, true);
        int candidateSellCount = metricsCalculator.countBatchesInRange(
                yesterdayCandidateBatches, dayStart, dayEnd, false);
        BigDecimal candidateNetReturn = metricsCalculator.sumNetReturn(yesterdayCandidateBatches, dayStart, dayEnd);
        List<String> candidateOpenStocks = metricsCalculator.extractOpenBatchStocks(candidateActiveBatches);

        CandidateShadowSummary candidateShadow = new CandidateShadowSummary(
                metricsCalculator.countOccupiedSlots(candidateSlots),
                candidateEquity.equity(), candidateEquity.cashAndReserved(), candidateEquity.missingPriceStocks(),
                candidateBuyCount, candidateSellCount, candidateNetReturn, candidateOpenStocks);

        List<TornStockSignalEventDO> signalEvents = querySignalEventsByTimeRange(dayStart, dayEnd);
        int signalCount = metricsCalculator.countSignalEvents(signalEvents);
        int shadowNewCount = metricsCalculator.countShadowDecisions(signalEvents);
        int fullRejectCount = metricsCalculator.countNoAvailableSlotRejections(signalEvents);
        int styleRejectCount = metricsCalculator.countStyleReject(signalEvents);

        List<TornStockVirtualBatchDO> shadowBatches =
                dedupById(virtualBatchDAO.selectShadowActionBatches(dayStart, dayEnd));
        int highRiskCount = metricsCalculator.countHighRisk(shadowBatches);

        List<TornStockBatchMarkDO> researchMarks =
                batchMarkDAO.selectDynamicShadowResearchMarks(dayStart, dayEnd);
        DynamicSellResearchSummaryCalculator.DynamicSellResearchSummary researchSummary =
                dynamicSellResearchCalculator.summarize(researchMarks);

        ShadowSummary shadow = new ShadowSummary(signalCount, shadowNewCount, fullRejectCount,
                styleRejectCount, researchSummary.researchMarkCount(),
                researchSummary.completeResearchMarkCount(), highRiskCount);

        return new DailySummaryData(formal, candidateShadow, shadow);
    }

    /**
     * 批量加载最新且处于新鲜度窗口内的bar,按股票ID索引避免N+1查询。
     * <p>
     * 正式与候选影子开放仓位的股票ID合并为一次查询,保证每个摘要周期最多一次行情批量读取。
     *
     * @param formalOpenBatches    正式开放仓位
     * @param candidateOpenBatches 候选影子开放仓位
     * @param generatedAt          日报生成时点
     * @return 按股票ID索引的最新bar映射
     */
    private Map<Integer, TornStockMarketBar15mDO> loadLatestBars(
            List<TornStockVirtualBatchDO> formalOpenBatches,
            List<TornStockVirtualBatchDO> candidateOpenBatches,
            LocalDateTime generatedAt) {
        List<Integer> stocksIds = Stream.concat(formalOpenBatches.stream(), candidateOpenBatches.stream())
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
     * 查询指定时间范围内的全部信号事件。
     * <p>
     * 使用MyBatis-Plus lambdaQuery按roundTime范围过滤,避免逐轮次查询产生N+1。
     *
     * @param dayStart 摘要日期起始(含)
     * @param dayEnd   摘要日期结束(不含)
     * @return 信号事件列表
     */
    private List<TornStockSignalEventDO> querySignalEventsByTimeRange(
            LocalDateTime dayStart, LocalDateTime dayEnd) {
        return signalEventDAO.lambdaQuery()
                .ge(TornStockSignalEventDO::getRoundTime, dayStart)
                .lt(TornStockSignalEventDO::getRoundTime, dayEnd)
                .list();
    }

    /**
     * 按批次ID确定性去重,保证"昨日动作"批次在内存中不重复。
     *
     * @param batches 批次列表
     * @return 去重后的批次列表(保留首个出现的记录)
     */
    private List<TornStockVirtualBatchDO> dedupById(List<TornStockVirtualBatchDO> batches) {
        if (CollectionUtils.isEmpty(batches)) {
            return List.of();
        }
        return batches.stream()
                .filter(batch -> batch.getId() != null)
                .collect(Collectors.toMap(
                        TornStockVirtualBatchDO::getId,
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new))
                .values().stream().toList();
    }
}
