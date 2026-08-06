package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMonthlyStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockStrategyFeature15mDO;
import pn.torn.goldeneye.torn.service.stocks.alert.*;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.DeepMeanReversionBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.RangeLowerBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StrictReboundConfirmBuyStrategy;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.StockCandidateRankingPolicy;
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
 * <p>注意: {@link StockBuySignalEvaluator} 构造参数中的DAO/Shadow写入器传null,
 * 仅调用其无写副作用的 {@code evaluateSignals} 方法。</p>
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
        this.buyEvaluator = new StockBuySignalEvaluator(
                buyStrategies, new StockEligibilityService(), portfolioService, null, null);
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
