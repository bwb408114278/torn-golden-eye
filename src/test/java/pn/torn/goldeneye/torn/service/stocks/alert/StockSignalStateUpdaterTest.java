package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.buy.StockBuyStrategy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 股票信号状态更新器测试,覆盖按股票×策略×规则版本去重、同轮状态合并和冷却回写。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.28
 */
@DisplayName("股票信号状态更新器测试")
@ExtendWith(MockitoExtension.class)
class StockSignalStateUpdaterTest {

    private static final Integer STOCKS_ID = 1001;
    private static final LocalDateTime ROUND_TIME = LocalDateTime.of(2026, 7, 28, 10, 0);
    private static final String BUY_RULE_VERSION = StockRoundTransactionService.BUY_RULE_VERSION;

    @Mock
    private TornStockSignalStateDAO signalStateDAO;

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("重复评估_同一复合键只批量保存一条状态")
    void updateStates_duplicateEvaluation_savesOneStatePerCompositeKey() {
        StockSignalStateUpdater updater = new StockSignalStateUpdater(signalStateDAO);
        StockBuyStrategy strategy = mockStrategy();
        StockSignalStateUpdater.SignalStateEvaluationView evaluation =
                evaluation(List.of(strategy), List.of(strategy));

        updater.updateStates(List.of(evaluation, evaluation), Map.of(), ROUND_TIME);

        ArgumentCaptor<List<TornStockSignalStateDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(signalStateDAO).saveOrUpdateBatch(captor.capture());
        List<TornStockSignalStateDO> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals(STOCKS_ID, saved.getFirst().getStocksId());
        assertEquals(StockBuyStrategyEnum.RANGE_LOWER_BUY.getCode(), saved.getFirst().getStrategyType());
        assertTrue(saved.getFirst().getConditionActive());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("重复评估_优先使用本轮最新状态计算复位")
    void updateStates_duplicateEvaluation_usesCurrentRoundStateForReset() {
        StockSignalStateUpdater updater = new StockSignalStateUpdater(signalStateDAO);
        StockBuyStrategy strategy = mockStrategy();
        StockSignalStateUpdater.SignalStateEvaluationView activeEvaluation =
                evaluation(List.of(strategy), List.of(strategy));
        StockSignalStateUpdater.SignalStateEvaluationView inactiveEvaluation =
                evaluation(List.of(strategy), List.of());

        updater.updateStates(List.of(activeEvaluation, inactiveEvaluation), Map.of(), ROUND_TIME);

        ArgumentCaptor<List<TornStockSignalStateDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(signalStateDAO).saveOrUpdateBatch(captor.capture());
        TornStockSignalStateDO saved = captor.getValue().getFirst();
        assertFalse(saved.getConditionActive());
        assertTrue(saved.getResetObserved());
        assertEquals(ROUND_TIME, saved.getLastSignalTime());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("平仓回写_复用本轮状态并保留边沿字段")
    void updateCloseStates_reusesCurrentStateAndPreservesEdgeFields() {
        StockSignalStateUpdater updater = new StockSignalStateUpdater(signalStateDAO);
        StockSignalStateKey key = new StockSignalStateKey(
                STOCKS_ID, StockBuyStrategyEnum.RANGE_LOWER_BUY.getCode(), BUY_RULE_VERSION);
        TornStockSignalStateDO state = new TornStockSignalStateDO();
        state.setStocksId(STOCKS_ID);
        state.setStrategyType(key.strategyType());
        state.setBuyRuleVersion(BUY_RULE_VERSION);
        state.setConditionActive(true);
        state.setLastSignalTime(ROUND_TIME);

        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(1L);
        batch.setStocksId(STOCKS_ID);
        batch.setPrimaryStrategy(key.strategyType());
        batch.setBuyRuleVersion(BUY_RULE_VERSION);
        batch.setCooldownUntil(ROUND_TIME.plusHours(24));
        batch.setExitReason("CLOSED_TARGET");

        updater.updateCloseStates(List.of(batch), Map.of(key, state));

        ArgumentCaptor<List<TornStockSignalStateDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(signalStateDAO).saveOrUpdateBatch(captor.capture());
        TornStockSignalStateDO saved = captor.getValue().getFirst();
        assertSame(state, saved);
        assertEquals(ROUND_TIME, saved.getLastSignalTime());
        assertEquals(batch.getCooldownUntil(), saved.getCooldownUntil());
        assertEquals(batch.getExitReason(), saved.getLastCloseType());
        assertFalse(saved.getResetObserved());
    }

    private StockBuyStrategy mockStrategy() {
        StockBuyStrategy strategy = mock(StockBuyStrategy.class);
        when(strategy.getStrategyType()).thenReturn(StockBuyStrategyEnum.RANGE_LOWER_BUY);
        return strategy;
    }

    private StockSignalStateUpdater.SignalStateEvaluationView evaluation(
            List<StockBuyStrategy> evaluatedStrategies,
            List<StockBuyStrategy> matchedStrategies) {
        return new StockSignalStateUpdater.SignalStateEvaluationView() {
            @Override
            public Integer stocksId() {
                return STOCKS_ID;
            }

            @Override
            public List<StockBuyStrategy> evaluatedStrategies() {
                return evaluatedStrategies;
            }

            @Override
            public List<StockBuyStrategy> matchedStrategies() {
                return matchedStrategies;
            }
        };
    }
}
