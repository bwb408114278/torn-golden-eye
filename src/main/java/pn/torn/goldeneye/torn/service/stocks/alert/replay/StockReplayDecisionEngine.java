package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockEligibilityResultEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBatchExitService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEligibilityService;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.BuyContext;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 回放决策引擎，只复用纯买入策略、资格判断和正式退出规则，不调用正式写入编排。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
public class StockReplayDecisionEngine {
    private final StockReplayPortfolioEngine portfolioEngine;
    private final List<StockBuyStrategy> buyStrategies;
    private final StockEligibilityService eligibilityService;
    private final StockBatchExitService batchExitService;

    public StockReplayDecisionEngine(StockReplayPortfolioEngine portfolioEngine,
                                     List<StockBuyStrategy> buyStrategies,
                                     StockEligibilityService eligibilityService) {
        this.portfolioEngine = Objects.requireNonNull(portfolioEngine, "资金引擎不能为空");
        this.buyStrategies = List.copyOf(buyStrategies == null ? List.of() : buyStrategies);
        this.eligibilityService = Objects.requireNonNull(eligibilityService, "资格服务不能为空");
        this.batchExitService = new StockBatchExitService();
    }

    /**
     * 为正式轨道候选分配第一个空闲槽位。
     *
     * @param state        正式轨道状态
     * @param stocksId     股票ID
     * @param entryPrice   入场参考价
     * @param decisionTime 决策时间
     * @return 分配结果
     */
    public Decision allocateFormal(StockReplayPortfolioState state, Integer stocksId,
                                   BigDecimal entryPrice, LocalDateTime decisionTime) {
        if (state == null || state.track() != StockReplayTrackEnum.FORMAL_5_SLOT) {
            throw new IllegalArgumentException("只能为正式五槽轨道分配候选");
        }
        if (stocksId == null || entryPrice == null || decisionTime == null) {
            throw new IllegalArgumentException("候选分配参数不能为空");
        }
        return state.slots().stream()
                .filter(slot -> slot.stocksId() == null && slot.reservedCash().signum() == 0)
                .findFirst()
                .map(slot -> new Decision(portfolioEngine.reserve(state, slot.slotNo()),
                        "ACCEPTED", null, slot.slotNo(), decisionTime))
                .orElseGet(() -> new Decision(state, "NO_AVAILABLE_SLOT",
                        "正式轨道无可用槽位", null, decisionTime));
    }

    /**
     * 使用无信号状态的正式策略列表计算单个股票买入资格。
     *
     * @param context              买入上下文
     * @param signalState          信号状态
     * @param hasActiveFormalBatch 是否存在正式活跃批次
     * @param decisionTime         显式决策时间
     * @return 买入决策
     */
    public BuyDecision evaluateBuy(BuyContext context, TornStockSignalStateDO signalState,
                                   boolean hasActiveFormalBatch, LocalDateTime decisionTime) {
        return evaluateBuyInternal(context, signalState, hasActiveFormalBatch, decisionTime);
    }

    /**
     * 使用批量加载的信号状态计算买入资格，按股票、策略和规则版本选择状态。
     *
     * @param context              买入上下文
     * @param signalStates         批量信号状态
     * @param buyRuleVersion       买入规则版本
     * @param hasActiveFormalBatch 是否存在正式活跃批次
     * @param decisionTime         显式决策时间
     * @return 买入决策
     */
    public BuyDecision evaluateBuyWithSignalStates(BuyContext context,
                                                   List<TornStockSignalStateDO> signalStates,
                                                   String buyRuleVersion,
                                                   boolean hasActiveFormalBatch,
                                                   LocalDateTime decisionTime) {
        BuyDecision matchedDecision = evaluateBuyInternal(context, null,
                hasActiveFormalBatch, decisionTime);
        if (!matchedDecision.accepted() || signalStates == null) {
            return matchedDecision;
        }
        TornStockSignalStateDO state = signalStates.stream()
                .filter(item -> context.stocksId().equals(item.getStocksId()))
                .filter(item -> matchedDecision.strategyCode().equals(item.getStrategyType()))
                .filter(item -> buyRuleVersion == null || buyRuleVersion.equals(item.getBuyRuleVersion()))
                .findFirst().orElse(null);
        return evaluateBuyInternal(context, state, hasActiveFormalBatch, decisionTime);
    }

    private BuyDecision evaluateBuyInternal(BuyContext context, TornStockSignalStateDO signalState,
                                            boolean hasActiveFormalBatch, LocalDateTime decisionTime) {
        Objects.requireNonNull(context, "买入上下文不能为空");
        Objects.requireNonNull(decisionTime, "决策时间不能为空");
        if (!Boolean.TRUE.equals(context.strategyReady())) {
            return BuyDecision.rejected("DATA_NOT_READY", null);
        }
        if (context.stylePrior() == null) {
            return BuyDecision.rejected("STYLE_MISSING", null);
        }
        if (context.maturity() == null || !context.maturity().isUsable()) {
            return BuyDecision.rejected("MATURITY_INSUFFICIENT", null);
        }
        if (!hasRequiredStrategyInputs(context)) {
            return BuyDecision.rejected("DATA_INSUFFICIENT", null);
        }
        List<StrategyScore> matched = new ArrayList<>();
        for (StockBuyStrategy strategy : buyStrategies) {
            if (strategy.isApplicableStyle(context.stylePrior()) && strategy.matches(context)) {
                matched.add(new StrategyScore(strategy, strategy.calculateQualityScore(context)));
            }
        }
        if (matched.isEmpty()) {
            return BuyDecision.rejected("NO_BUY_STRATEGY_MATCH", null);
        }
        matched.sort(Comparator.comparing(StrategyScore::score).reversed()
                .thenComparing(item -> item.strategy().getStrategyType().getCode()));
        StockEligibilityService.EligibilityResult eligibility = eligibilityService.checkEligibility(
                context, signalState, null, hasActiveFormalBatch, decisionTime);
        if (eligibility.result() != StockEligibilityResultEnum.ALLOWED) {
            return BuyDecision.rejected(eligibility.reasons().getFirst(), matched.getFirst().strategy()
                    .getStrategyType().getCode());
        }
        return new BuyDecision(true, "ACCEPTED", matched.getFirst().strategy().getStrategyType().getCode(),
                matched.getFirst().score());
    }

    /**
     * 复用正式批次退出规则，不修改传入批次。
     *
     * @param batch        退出评估批次
     * @param currentPrice 当前价
     * @param position30   区间位置
     * @param low30d       30日低点
     * @param high30d      30日高点
     * @param decisionTime 显式决策时间
     * @return 正式退出评估结果
     */
    public StockBatchExitService.ExitEvaluation evaluateSell(
            TornStockVirtualBatchDO batch, BigDecimal currentPrice, BigDecimal position30,
            BigDecimal low30d, BigDecimal high30d, LocalDateTime decisionTime) {
        return batchExitService.evaluateExit(batch, currentPrice, position30, low30d, high30d, decisionTime);
    }

    private boolean hasRequiredStrategyInputs(BuyContext context) {
        return context.referencePrice() != null
                && context.ma7d() != null
                && context.ma30d() != null
                && context.zscore1d() != null
                && context.return6h() != null
                && context.return1d() != null
                && context.return7d() != null
                && context.low30d() != null
                && context.high30d() != null
                && context.width30d() != null
                && context.position30() != null
                && context.pctAbove30dLow() != null;
    }

    /**
     * 分配决策。
     */
    public record Decision(StockReplayPortfolioState state, String reason, String detail,
                           Integer slotNo, LocalDateTime decisionTime) {
    }

    /**
     * 买入决策。
     */
    public record BuyDecision(boolean accepted, String reason, String strategyCode, BigDecimal qualityScore) {
        static BuyDecision rejected(String reason, String strategyCode) {
            return new BuyDecision(false, reason, strategyCode, null);
        }
    }

    private record StrategyScore(StockBuyStrategy strategy, BigDecimal score) {
    }
}
