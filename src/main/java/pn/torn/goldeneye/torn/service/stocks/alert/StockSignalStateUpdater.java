package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 股票信号状态更新器 - 执行轮次事务步骤10,更新信号边沿状态。
 * <p>
 * 本服务按股票与策略分别维护条件状态,避免主策略切换时覆盖其他策略的边沿、复位和冷却数据。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockSignalStateUpdater {

    private final TornStockSignalStateDAO signalStateDAO;

    /**
     * 处理全部策略状态,每个股票和策略使用独立状态键。
     *
     * @param allEvaluations   全部信号评估结果
     * @param signalStateByKey 按股票、策略和规则版本索引的状态
     * @param roundTime        本轮时间
     */
    public void updateStates(
            List<? extends SignalStateEvaluationView> allEvaluations,
            Map<StockSignalStateKey, TornStockSignalStateDO> signalStateByKey,
            LocalDateTime roundTime) {
        Objects.requireNonNull(roundTime, "轮次时间不能为空");
        if (allEvaluations == null || allEvaluations.isEmpty()) {
            return;
        }

        Map<StockSignalStateKey, TornStockSignalStateDO> states = signalStateByKey == null
                ? Map.of() : signalStateByKey;
        List<TornStockSignalStateDO> toSave = new ArrayList<>();
        for (SignalStateEvaluationView evaluation : allEvaluations) {
            appendStrategyStates(evaluation, states, roundTime, toSave);
        }
        if (!toSave.isEmpty()) {
            signalStateDAO.saveOrUpdateBatch(toSave);
        }
        log.debug("信号状态按策略更新完成: count={}", toSave.size());
    }

    /**
     * 按评估结果中的全部策略更新状态。
     *
     * @param evaluation       股票评估结果
     * @param signalStateByKey 状态索引
     * @param roundTime        本轮时间
     * @param toSave           待保存状态
     */
    private void appendStrategyStates(SignalStateEvaluationView evaluation,
                                      Map<StockSignalStateKey, TornStockSignalStateDO> signalStateByKey,
                                      LocalDateTime roundTime,
                                      List<TornStockSignalStateDO> toSave) {
        List<StockBuyStrategy> strategies = evaluation.evaluatedStrategies();
        if (strategies == null || strategies.isEmpty()) {
            return;
        }
        for (StockBuyStrategy strategy : strategies) {
            StockSignalStateKey key = new StockSignalStateKey(
                    evaluation.stocksId(), strategy.getStrategyType().getCode(),
                    StockRoundTransactionService.BUY_RULE_VERSION);
            TornStockSignalStateDO state = signalStateByKey.get(key);
            boolean currentActive = evaluation.isStrategyMatched(strategy);
            toSave.add(updateState(state, evaluation, strategy, currentActive, roundTime));
        }
    }

    /**
     * 更新单个股票策略状态。
     *
     * @param state         已有状态,不存在时可为null
     * @param evaluation    股票评估结果
     * @param strategy      目标策略
     * @param currentActive 本轮策略是否命中
     * @param roundTime     本轮时间
     * @return 更新后的状态
     */
    private TornStockSignalStateDO updateState(TornStockSignalStateDO state,
                                               SignalStateEvaluationView evaluation,
                                               StockBuyStrategy strategy,
                                               boolean currentActive,
                                               LocalDateTime roundTime) {
        TornStockSignalStateDO target = state == null ? new TornStockSignalStateDO() : state;
        boolean previousActive = Boolean.TRUE.equals(target.getConditionActive());
        target.setStocksId(evaluation.stocksId());
        target.setStrategyType(strategy.getStrategyType().getCode());
        target.setBuyRuleVersion(StockRoundTransactionService.BUY_RULE_VERSION);
        target.setConditionActive(currentActive);
        target.setLastEvaluatedRoundTime(roundTime);
        if (currentActive && !previousActive) {
            target.setLastSignalTime(roundTime);
        }
        if (!currentActive && previousActive) {
            target.setResetObserved(true);
        }
        if (target.getResetObserved() == null) {
            target.setResetObserved(false);
        }
        return target;
    }

    /**
     * 股票策略评估状态视图。
     *
     * @author Bai
     * @version 1.2.12
     * @since 2026.07.27
     */
    public interface SignalStateEvaluationView {

        /**
         * 获取股票ID。
         *
         * @return 股票ID
         */
        Integer stocksId();

        /**
         * 获取本轮已评估策略。
         *
         * @return 策略列表
         */
        List<StockBuyStrategy> evaluatedStrategies();

        /**
         * 获取本轮命中的策略。
         *
         * @return 命中策略列表
         */
        List<StockBuyStrategy> matchedStrategies();

        /**
         * 判断指定策略本轮是否命中。
         *
         * @param strategy 目标策略
         * @return 命中时返回true
         */
        default boolean isStrategyMatched(StockBuyStrategy strategy) {
            return matchedStrategies().contains(strategy);
        }
    }
}
