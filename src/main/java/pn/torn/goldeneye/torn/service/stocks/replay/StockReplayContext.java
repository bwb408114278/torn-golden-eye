package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.BuyContextAssembler;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.BuyEligibilityEvaluator;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.BuyStrategyMatcher;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.CandidateFactory;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.policy.CandidateInfo;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.DailySummaryMetricsCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.DeepMeanReversionBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.DynamicSellResearchSummaryCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.PortfolioEquityCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.RangeLowerBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mBarBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.Stock15mFeatureBuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockAlertRuntimeGate;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockBatchExitService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockBatchPathService;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockBuySignalEvaluator;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockBuySignalResult;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.StockBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockCandidateAllocationResult;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.policy.StockCandidateRankingPolicy;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockCandidateTrackAllocationService;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryNoticeService;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryQueryService;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryRenderer;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDailySummaryService;
import pn.torn.goldeneye.torn.service.stocks.alert.summary.StockDynamicSellResearchConstants;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockEligibilityService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockEntrySettlementService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockHashUtils;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockHistoryRebuildService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketClock;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundFactory;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockMarketRoundLoader;
import pn.torn.goldeneye.torn.service.stocks.alert.monthly.StockMonthlyEvidenceComputer;
import pn.torn.goldeneye.torn.service.stocks.alert.monthly.StockMonthlyEvidenceMetrics;
import pn.torn.goldeneye.torn.service.stocks.alert.monthly.StockMonthlyPrevious;
import pn.torn.goldeneye.torn.service.stocks.alert.monthly.StockMonthlyStateCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.monthly.StockMonthlyStateDraft;
import pn.torn.goldeneye.torn.service.stocks.alert.monthly.StockMonthlyStateInitService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioInitService;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockPortfolioService;
import pn.torn.goldeneye.torn.service.stocks.alert.observation.StockRejectedObservationCalculator;
import pn.torn.goldeneye.torn.service.stocks.alert.observation.StockRejectedObservationService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.round.StockRoundExitGuard;
import pn.torn.goldeneye.torn.service.stocks.alert.market.round.StockRoundTransactionService;
import pn.torn.goldeneye.torn.service.stocks.alert.market.StockRuleVersion;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowRecordWriter;
import pn.torn.goldeneye.torn.service.stocks.alert.shadow.StockShadowTrackRecorder;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockSignalEventContext;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockSignalStateKey;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.StockSignalStateUpdater;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.StockStrategyUtils;
import pn.torn.goldeneye.torn.service.stocks.alert.portfolio.StockVirtualBatchAssembler;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.StrictReboundConfirmBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.market.round.VipStockAlertScheduler;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.DeepMeanReversionBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.RangeLowerBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.StockBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.strategy.StrictReboundConfirmBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.policy.StockCandidateRankingPolicy;
import pn.torn.goldeneye.torn.service.stocks.replay.model.StockReplayRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 回放不可变上下文。
 *
 * <p>封装回放请求、只读窗口数据与可复用纯领域组件。以下组件均以无状态实例方式复用
 * 正式纯领域实现(策略匹配、资格判定、入场/出场结算、持仓路径与退出评估),不依赖
 * Spring容器、DAO写、通知或系统时钟: 策略族 {@link StockBuyStrategy}、排序策略、
 * {@link StockEligibilityService}、{@link StockEntrySettlementService}、
 * {@link StockBatchPathService}、{@link StockBuySignalEvaluator}。</p>
 *
 * <p>注意: {@link StockBuySignalEvaluator} 为纯规则评估门面,回放仅调用其无写副作用的
 * {@code evaluateSignals} 方法。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
public class StockReplayContext {

    private final StockReplayRequest request;
    private final StockReplayWindowData windowData;
    private final List<StockBuyStrategy> buyStrategies;
    private final StockCandidateRankingPolicy rankingPolicy;
    private final StockPortfolioService portfolioService;
    private final StockEntrySettlementService entrySettlementService;
    private final StockBatchPathService pathService;
    private final StockBuySignalEvaluator buyEvaluator;
    private final StockBatchExitService exitService;

    /**
     * 构造回放上下文。
     *
     * @param request    回放请求
     * @param windowData 只读窗口数据
     */
    public StockReplayContext(StockReplayRequest request, StockReplayWindowData windowData) {
        this.request = request;
        this.windowData = windowData;
        this.buyStrategies = List.of(
                new DeepMeanReversionBuyStrategy(),
                new RangeLowerBuyStrategy(),
                new StrictReboundConfirmBuyStrategy());
        this.rankingPolicy = new StockCandidateRankingPolicy();
        this.portfolioService = new StockPortfolioService();
        this.entrySettlementService = new StockEntrySettlementService(portfolioService);
        this.exitService = new StockBatchExitService();
        this.pathService = new StockBatchPathService(exitService);
        BuyContextAssembler contextAssembler = new BuyContextAssembler();
        BuyStrategyMatcher strategyMatcher = new BuyStrategyMatcher(buyStrategies);
        BuyEligibilityEvaluator eligibilityEvaluator =
                new BuyEligibilityEvaluator(new StockEligibilityService());
        CandidateFactory candidateFactory = new CandidateFactory();
        this.buyEvaluator = new StockBuySignalEvaluator(
                buyStrategies, contextAssembler, strategyMatcher, eligibilityEvaluator, candidateFactory);
    }

    /**
     * 回放请求。
     *
     * @return 回放请求
     */
    public StockReplayRequest request() {
        return request;
    }

    /**
     * 只读窗口数据。
     *
     * @return 窗口数据
     */
    public StockReplayWindowData windowData() {
        return windowData;
    }

    /**
     * 候选排序策略。
     *
     * @return 排序策略
     */
    public StockCandidateRankingPolicy rankingPolicy() {
        return rankingPolicy;
    }

    /**
     * 正式资金组合服务(仅使用内存槽位运算与静态计算)。
     *
     * @return 资金组合服务
     */
    public StockPortfolioService portfolioService() {
        return portfolioService;
    }

    /**
     * 入场/出场结算服务。
     *
     * @return 结算服务
     */
    public StockEntrySettlementService entrySettlementService() {
        return entrySettlementService;
    }

    /**
     * 持仓路径与退出评估服务。
     *
     * @return 路径服务
     */
    public StockBatchPathService pathService() {
        return pathService;
    }

    /**
     * 买入信号评估器。
     *
     * @return 信号评估器
     */
    public StockBuySignalEvaluator buyEvaluator() {
        return buyEvaluator;
    }

    /**
     * 指定轮次全市场bar(按股票ID索引)。
     *
     * @param time 轮次时间
     * @return 股票ID → bar
     */
    public Map<Integer, TornStockMarketBar15mDO> barsAt(LocalDateTime time) {
        return indexAtTime(windowData.barsByStock(), time);
    }

    /**
     * 指定轮次全市场特征(按股票ID索引)。
     *
     * @param time 轮次时间
     * @return 股票ID → 特征
     */
    public Map<Integer, TornStockStrategyFeature15mDO> featuresAt(LocalDateTime time) {
        return indexAtTime(windowData.featuresByStock(), time);
    }

    /**
     * 指定轮次生效月份的已确认月度状态。
     *
     * @param time 轮次时间
     * @return 股票ID → 月度状态;该月无状态时返回空映射
     */
    public Map<Integer, TornStockMonthlyStateDO> monthlyStatesFor(LocalDateTime time) {
        LocalDate month = time.toLocalDate().withDayOfMonth(1);
        return windowData.monthlyStatesByMonth().getOrDefault(month, Map.of());
    }

    private static <T> Map<Integer, T> indexAtTime(
            Map<Integer, NavigableMap<LocalDateTime, T>> byStock, LocalDateTime time) {
        Map<Integer, T> result = new java.util.HashMap<>();
        for (Map.Entry<Integer, NavigableMap<LocalDateTime, T>> entry : byStock.entrySet()) {
            T value = entry.getValue().get(time);
            if (value != null) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }
}
