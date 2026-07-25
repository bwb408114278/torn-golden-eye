package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBuySignalEvaluator.SignalEvaluation;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 股票信号状态更新器 - 执行轮次事务步骤10,更新信号边沿状态。
 * <p>
 * 从 {@link StockRoundTransactionService} 拆分而来,消除原 {@code updateSignalStates}
 * 方法(认知复杂度16,含嵌套三元)的 Sonar 问题。职责仅包含:对每个有评估结果的股票,
 * 更新 {@link TornStockSignalStateDO#getConditionActive()} 为本轮 matches 结果,
 * 边沿触发时更新 lastSignalTime 与 lastEvaluatedRoundTime,
 * 条件从 true 变为 false 时标记复位已观察。
 *
 * <h3>Sonar修复要点</h3>
 * <ul>
 *   <li>原方法循环体提取为 {@link #updateSingleSignalState},降低认知复杂度(16 -&gt; 合规)</li>
 *   <li>嵌套三元 {@code primaryStrategy != null ? ... : matchedStrategies.isEmpty() ? null : ...}
 *       拆分为独立方法 {@link #resolveStrategyType}</li>
 *   <li>复位判断逻辑提取为 {@link #isResetObserved},消除循环内复杂条件</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockSignalStateUpdater {

    private final TornStockSignalStateDAO signalStateDAO;

    // ==================== 步骤10: 更新信号边沿状态 ====================

    /**
     * 更新信号边沿状态:记录本轮 matches 结果为 conditionActive,边沿触发时更新 lastSignalTime。
     * <p>
     * 对每个有评估结果的股票,更新 {@link TornStockSignalStateDO#getConditionActive()} 为本轮
     * matches 结果,边沿触发时更新 lastSignalTime 与 lastEvaluatedRoundTime。
     * 单支股票的状态更新逻辑提取为 {@link #updateSingleSignalState},返回待保存的 DO。
     *
     * @param allEvaluations     全部信号评估结果
     * @param signalStateByStock 按股票ID索引的信号状态映射
     * @param roundTime          本轮时间
     */
    public void updateStates(
            List<? extends StockBuySignalEvaluator.SignalEvaluation> allEvaluations,
            Map<Integer, TornStockSignalStateDO> signalStateByStock,
            LocalDateTime roundTime) {
        Objects.requireNonNull(roundTime, "轮次时间不能为空");
        if (allEvaluations == null || allEvaluations.isEmpty()) {
            return;
        }

        List<TornStockSignalStateDO> toSave = new ArrayList<>(allEvaluations.size());
        for (SignalEvaluation evaluation : allEvaluations) {
            toSave.add(updateSingleSignalState(evaluation, signalStateByStock, roundTime));
        }

        signalStateDAO.saveOrUpdateBatch(toSave);
        log.debug("信号边沿状态更新: count={}", toSave.size());
    }

    /**
     * 更新单支股票的信号状态,返回待保存的 DO。
     * <p>
     * 若 signalStateByStock 中不存在该股票的状态记录,则新建并初始化策略类型
     * (由 {@link #resolveStrategyType} 解析)、买入规则版本与复位标记。
     * 随后更新 conditionActive 为本轮 matches 结果、lastEvaluatedRoundTime 为本轮时间,
     * 边沿触发时更新 lastSignalTime,条件从 true 变为 false 时标记复位已观察
     * (由 {@link #isResetObserved} 判断)。
     *
     * @param evaluation         单支股票的信号评估结果
     * @param signalStateByStock 按股票ID索引的信号状态映射
     * @param roundTime          本轮时间
     * @return 待保存的信号状态 DO
     */
    private TornStockSignalStateDO updateSingleSignalState(
            SignalEvaluation evaluation,
            Map<Integer, TornStockSignalStateDO> signalStateByStock,
            LocalDateTime roundTime) {
        TornStockSignalStateDO state = signalStateByStock.get(evaluation.stocksId());

        if (state == null) {
            state = new TornStockSignalStateDO();
            state.setStocksId(evaluation.stocksId());
            state.setStrategyType(resolveStrategyType(evaluation));
            state.setBuyRuleVersion(StockRoundTransactionService.BUY_RULE_VERSION);
            state.setResetObserved(false);
        }

        boolean previousActive = Boolean.TRUE.equals(state.getConditionActive());
        state.setConditionActive(evaluation.currentMatches());
        state.setLastEvaluatedRoundTime(roundTime);

        if (evaluation.edgeTriggered()) {
            state.setLastSignalTime(roundTime);
        }

        if (isResetObserved(evaluation, previousActive)) {
            state.setResetObserved(true);
        }
        return state;
    }

    /**
     * 解析信号评估结果对应的策略类型编码。
     * <p>
     * 优先取主策略(primaryStrategy)的类型编码;主策略为空且无命中策略时返回 null;
     * 否则取首个命中策略的类型编码。
     * <p>
     * 此方法将原 {@code updateSingleSignalState} 中的嵌套三元
     * {@code primaryStrategy != null ? ... : matchedStrategies.isEmpty() ? null : ...}
     * 拆分为独立方法,消除 Sonar 嵌套三元告警。
     *
     * @param evaluation 信号评估结果
     * @return 策略类型编码;无策略命中时返回 null
     */
    private String resolveStrategyType(SignalEvaluation evaluation) {
        if (evaluation.primaryStrategy() != null) {
            return evaluation.primaryStrategy().getStrategyType().getCode();
        }
        if (evaluation.matchedStrategies().isEmpty()) {
            return null;
        }
        StockBuyStrategy firstMatched = evaluation.matchedStrategies().getFirst();
        return firstMatched.getStrategyType().getCode();
    }

    /**
     * 判断是否应标记复位已观察。
     * <p>
     * 当本轮条件不再满足(currentMatches 为 false)且上轮条件处于激活状态(previousActive 为 true)
     * 时,表示条件从 true 变为 false,应标记复位已观察。
     * <p>
     * 此方法从 {@code updateSingleSignalState} 提取,消除循环内复杂条件判断,
     * 降低认知复杂度。
     *
     * @param evaluation     信号评估结果
     * @param previousActive 上轮 conditionActive 是否为 true
     * @return true 表示应标记复位已观察
     */
    private boolean isResetObserved(SignalEvaluation evaluation, boolean previousActive) {
        return !evaluation.currentMatches() && previousActive;
    }
}
