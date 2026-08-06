package pn.torn.goldeneye.torn.service.stocks.alert;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 股票信号状态更新器 - 执行轮次事务步骤10,更新信号边沿状态。
 * <p>
 * 本服务按股票与策略分别维护条件状态,避免主策略切换时覆盖其他策略的边沿、复位和冷却数据。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockSignalStateUpdater {

    private final TornStockSignalStateDAO signalStateDAO;

    /**
     * 将已成交平仓批次的冷却与复位事实回写到策略状态。
     * <p>
     * 同一轮已有状态优先,随后回写平仓冷却与复位字段,避免旧快照覆盖本轮边沿状态。
     * <p>
     * 每个平仓批次按股票、主策略和买入规则版本更新对应状态。
     *
     * @param signalStateByKey 按股票、策略和规则版本索引的状态
     */
    public void updateCloseStates(List<TornStockVirtualBatchDO> closedBatches,
                                  Map<StockSignalStateKey, TornStockSignalStateDO> signalStateByKey) {
        if (closedBatches == null || closedBatches.isEmpty()) {
            return;
        }
        Map<StockSignalStateKey, TornStockSignalStateDO> toSaveByKey = new LinkedHashMap<>();
        for (TornStockVirtualBatchDO batch : closedBatches) {
            if (!pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum.FORMAL.getCode()
                    .equals(batch == null ? null : batch.getLedgerType())) {
                continue;
            }
            appendCloseState(batch, signalStateByKey, toSaveByKey);
        }
        List<TornStockSignalStateDO> toSave = new ArrayList<>(toSaveByKey.values());
        if (!toSave.isEmpty()) {
            signalStateDAO.saveOrUpdateBatch(toSave);
        }
        log.debug("平仓冷却状态回写完成: count={}", toSave.size());
    }

    /**
     * 将单个平仓批次映射到对应策略状态。
     *
     * @param batch            已成交平仓批次
     * @param signalStateByKey 状态索引
     */
    private void appendCloseState(TornStockVirtualBatchDO batch,
                                  Map<StockSignalStateKey, TornStockSignalStateDO> signalStateByKey,
                                  Map<StockSignalStateKey, TornStockSignalStateDO> toSaveByKey) {
        if (batch == null || batch.getStocksId() == null
                || batch.getPrimaryStrategy() == null || batch.getBuyRuleVersion() == null) {
            log.warn("平仓批次缺少状态键,跳过冷却回写: batchId={}", batch == null ? null : batch.getId());
            return;
        }
        StockSignalStateKey key = new StockSignalStateKey(
                batch.getStocksId(), batch.getPrimaryStrategy(), batch.getBuyRuleVersion());
        TornStockSignalStateDO state = toSaveByKey.get(key);
        if (state == null && signalStateByKey != null) {
            state = signalStateByKey.get(key);
        }
        if (state == null) {
            state = new TornStockSignalStateDO();
        }
        state.setStocksId(batch.getStocksId());
        state.setStrategyType(batch.getPrimaryStrategy());
        state.setBuyRuleVersion(batch.getBuyRuleVersion());
        state.setCooldownUntil(batch.getCooldownUntil());
        state.setLastCloseType(resolveLastCloseType(batch));
        state.setResetObserved(false);
        toSaveByKey.put(key, state);
    }

    /**
     * 解析批次最终关闭类型写入信号状态。
     * <p>
     * 优先使用批次终态{@code batchStatus}: 灾难处置统一将批次置为{@code ADMIN_CLOSED},
     * 此时必须记录最终管理关闭类型,而不能沿用原策略退出原因{@code exitReason}
     * (如CLOSED_TARGET)造成跨表审计语义不一致。普通策略关闭时{@code batchStatus}
     * 由{@code exitReason}派生,二者一致。批次终态缺失时兜底使用{@code exitReason}。
     *
     * @param batch 已平仓批次
     * @return 批次最终关闭类型编码
     */
    private String resolveLastCloseType(TornStockVirtualBatchDO batch) {
        if (batch.getBatchStatus() != null && !batch.getBatchStatus().isBlank()) {
            return batch.getBatchStatus();
        }
        return batch.getExitReason();
    }

    /**
     * 更新全部策略信号状态。
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
                ? new LinkedHashMap<>() : signalStateByKey;
        Map<StockSignalStateKey, TornStockSignalStateDO> toSaveByKey = new LinkedHashMap<>();
        for (SignalStateEvaluationView evaluation : allEvaluations) {
            appendStrategyStates(evaluation, states, toSaveByKey, roundTime);
        }
        List<TornStockSignalStateDO> toSave = new ArrayList<>(toSaveByKey.values());
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
     * @param toSaveByKey      本轮已合并的待保存状态
     * @param roundTime        本轮时间
     */
    private void appendStrategyStates(SignalStateEvaluationView evaluation,
                                      Map<StockSignalStateKey, TornStockSignalStateDO> signalStateByKey,
                                      Map<StockSignalStateKey, TornStockSignalStateDO> toSaveByKey,
                                      LocalDateTime roundTime) {
        if (evaluation == null || evaluation.stocksId() == null) {
            return;
        }
        List<StockBuyStrategy> strategies = evaluation.evaluatedStrategies();
        if (strategies == null || strategies.isEmpty()) {
            return;
        }
        for (StockBuyStrategy strategy : strategies) {
            if (strategy == null || strategy.getStrategyType() == null) {
                continue;
            }
            StockSignalStateKey key = new StockSignalStateKey(
                    evaluation.stocksId(), strategy.getStrategyType().getCode(),
                    StockRoundTransactionService.BUY_RULE_VERSION);
            TornStockSignalStateDO state = toSaveByKey.get(key);
            if (state == null && signalStateByKey != null) {
                state = signalStateByKey.get(key);
            }
            boolean currentActive = evaluation.isStrategyMatched(strategy);
            toSaveByKey.put(key, updateState(state, evaluation, strategy, currentActive, roundTime));
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
        target.applyEvaluation(
                evaluation.stocksId(),
                strategy.getStrategyType().getCode(),
                StockRoundTransactionService.BUY_RULE_VERSION,
                currentActive,
                roundTime);
        return target;
    }

    /**
     * 股票策略评估状态视图。
     *
     * @author Bai
     * @version 1.2.14
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
