package pn.torn.goldeneye.torn.service.stocks.alert.signal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockSignalStateDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalStateDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import pn.torn.goldeneye.torn.service.stocks.alert.market.round.StockRoundTransactionService;

/**
 * 股票信号状态账本隔离测试，确保Shadow平仓不会改写正式冷却状态。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.27
 */
@DisplayName("股票信号状态账本隔离测试")
@ExtendWith(MockitoExtension.class)
class StockSignalStateLedgerIsolationTest {

    private static final LocalDateTime ROUND_TIME = LocalDateTime.of(2026, 7, 28, 10, 0);

    @Mock
    private TornStockSignalStateDAO signalStateDao;

    @Test
    @DisplayName("Shadow平仓_不得写入正式冷却状态")
    void shadowClose_doesNotWriteFormalCooldownState() {
        StockSignalStateUpdater updater = new StockSignalStateUpdater(signalStateDao);
        TornStockVirtualBatchDO shadowBatch = new TornStockVirtualBatchDO();
        shadowBatch.setId(1L);
        shadowBatch.setLedgerType(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        shadowBatch.setStocksId(1001);
        shadowBatch.setPrimaryStrategy("RANGE_LOWER_BUY");
        shadowBatch.setBuyRuleVersion(StockRoundTransactionService.BUY_RULE_VERSION);
        shadowBatch.setCooldownUntil(ROUND_TIME.plusHours(24));

        updater.updateCloseStates(List.of(shadowBatch), Map.of());

        verify(signalStateDao, org.mockito.Mockito.never()).saveOrUpdateBatch(org.mockito.Mockito.anyList());
    }

    @Test
    @DisplayName("正式平仓_回写正式冷却状态")
    void formalClose_writesFormalCooldownState() {
        StockSignalStateUpdater updater = new StockSignalStateUpdater(signalStateDao);
        TornStockVirtualBatchDO formalBatch = new TornStockVirtualBatchDO();
        formalBatch.setId(2L);
        formalBatch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());
        formalBatch.setStocksId(1001);
        formalBatch.setPrimaryStrategy("RANGE_LOWER_BUY");
        formalBatch.setBuyRuleVersion(StockRoundTransactionService.BUY_RULE_VERSION);
        formalBatch.setCooldownUntil(ROUND_TIME.plusHours(24));
        formalBatch.setExitReason("CLOSED_TARGET");

        updater.updateCloseStates(List.of(formalBatch), Map.of());

        ArgumentCaptor<List<TornStockSignalStateDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(signalStateDao).saveOrUpdateBatch(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(formalBatch.getCooldownUntil(), captor.getValue().getFirst().getCooldownUntil());
    }
}
