package pn.torn.goldeneye.torn.service.stocks.replay;

import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBuySignalEvaluator;
import pn.torn.goldeneye.torn.service.stocks.alert.StockRoundTransactionService;
import pn.torn.goldeneye.torn.service.stocks.alert.StockSignalStateKey;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回放信号状态机镜像。
 *
 * <p>按正式链口径在内存维护每(股票,策略)的信号状态: 条件激活、最近评估轮次、
 * 最近信号时间、复位观察与冷却。生产侧该状态由 {@code StockSignalStateUpdater} 持久化到
 * 业务表,回放受只读约束改为内存镜像,口径与生产一致。</p>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.06
 */
final class StockReplaySignalStateMirror {

    private final StockReplayPortfolio portfolio;

    /**
     * 构造信号状态镜像。
     *
     * @param portfolio 轨道组合(信号状态索引所在)
     */
    StockReplaySignalStateMirror(StockReplayPortfolio portfolio) {
        this.portfolio = portfolio;
    }

    /**
     * 按本轮全部信号评估更新信号状态。
     *
     * @param allEvaluations 本轮全部信号评估
     * @param roundTime      本轮时间
     */
    void updateFromEvaluations(List<StockBuySignalEvaluator.SignalEvaluation> allEvaluations,
                               LocalDateTime roundTime) {
        for (StockBuySignalEvaluator.SignalEvaluation evaluation : allEvaluations) {
            if (evaluation.stocksId() == null || evaluation.evaluatedStrategies() == null) {
                continue;
            }
            for (StockBuyStrategy strategy : evaluation.evaluatedStrategies()) {
                if (strategy == null || strategy.getStrategyType() == null) {
                    continue;
                }
                updateState(evaluation, strategy, roundTime);
            }
        }
    }

    /**
     * 按正式出场批次更新信号状态的冷却与最近平仓类型。
     *
     * @param formalExitFilled 本轮完成结算的正式出场批次
     */
    void updateFromFormalExits(List<TornStockVirtualBatchDO> formalExitFilled) {
        for (TornStockVirtualBatchDO batch : formalExitFilled) {
            if (batch == null || batch.getStocksId() == null || batch.getPrimaryStrategy() == null
                    || batch.getBuyRuleVersion() == null) {
                continue;
            }
            StockSignalStateKey key = new StockSignalStateKey(
                    batch.getStocksId(), batch.getPrimaryStrategy(), batch.getBuyRuleVersion());
            TornStockSignalStateDO state = portfolio.signalStates().get(key);
            if (state == null) {
                state = new TornStockSignalStateDO();
                portfolio.signalStates().put(key, state);
            }
            state.setCooldownUntil(batch.getCooldownUntil());
            state.setLastCloseType(resolveLastCloseType(batch));
            state.setResetObserved(false);
        }
    }

    private void updateState(StockBuySignalEvaluator.SignalEvaluation evaluation,
                             StockBuyStrategy strategy, LocalDateTime roundTime) {
        StockSignalStateKey key = new StockSignalStateKey(
                evaluation.stocksId(), strategy.getStrategyType().getCode(),
                StockRoundTransactionService.BUY_RULE_VERSION);
        TornStockSignalStateDO state = portfolio.signalStates().get(key);
        if (state == null) {
            state = new TornStockSignalStateDO();
            portfolio.signalStates().put(key, state);
        }
        apply(evaluation, strategy, state, roundTime);
    }

    private static void apply(StockBuySignalEvaluator.SignalEvaluation evaluation,
                              StockBuyStrategy strategy, TornStockSignalStateDO state,
                              LocalDateTime roundTime) {
        boolean currentActive = evaluation.matchedStrategies() != null
                && evaluation.matchedStrategies().contains(strategy);
        state.applyEvaluation(
                evaluation.stocksId(),
                strategy.getStrategyType().getCode(),
                StockRoundTransactionService.BUY_RULE_VERSION,
                currentActive,
                roundTime);
    }

    private static String resolveLastCloseType(TornStockVirtualBatchDO batch) {
        if (batch.getBatchStatus() != null && !batch.getBatchStatus().isBlank()) {
            return batch.getBatchStatus();
        }
        return batch.getExitReason();
    }
}
