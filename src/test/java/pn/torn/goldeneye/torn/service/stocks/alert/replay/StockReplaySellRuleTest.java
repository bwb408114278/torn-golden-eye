package pn.torn.goldeneye.torn.service.stocks.alert.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCloseTypeEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBatchExitService;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 股票回放纯卖出规则复用测试。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.30
 */
@DisplayName("股票回放纯卖出规则测试")
class StockReplaySellRuleTest {

    @Test
    @DisplayName("价格达到百分之零点八净收益_复用正式目标退出")
    void evaluateSell_targetReached_returnsTargetClose() {
        TornStockVirtualBatchDO batch = batch(LocalDateTime.of(2026, 1, 1, 10, 0));

        StockBatchExitService.ExitEvaluation result = new StockBatchExitService().evaluateExit(
                batch, new BigDecimal("101.0"), new BigDecimal("0.50"),
                new BigDecimal("90"), new BigDecimal("110"),
                LocalDateTime.of(2026, 1, 2, 10, 0));

        assertTrue(result.shouldExit());
        assertEquals(StockCloseTypeEnum.CLOSED_TARGET, result.closeType());
    }

    @Test
    @DisplayName("持有十四天_复用正式时间退出")
    void evaluateSell_holdFourteenDays_returnsTimeClose() {
        TornStockVirtualBatchDO batch = batch(LocalDateTime.of(2026, 1, 1, 10, 0));

        StockBatchExitService.ExitEvaluation result = new StockBatchExitService().evaluateExit(
                batch, new BigDecimal("100.00"), new BigDecimal("0.50"),
                new BigDecimal("90"), new BigDecimal("110"),
                LocalDateTime.of(2026, 1, 15, 10, 0));

        assertTrue(result.shouldExit());
        assertEquals(StockCloseTypeEnum.CLOSED_TIME, result.closeType());
    }

    private TornStockVirtualBatchDO batch(LocalDateTime entryTime) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setBatchNo("REPLAY-BATCH");
        batch.setEntryReferencePrice(new BigDecimal("100.00"));
        batch.setEntryTime(entryTime);
        batch.setPrimaryStrategy("DEEP_MEAN_REVERSION_BUY");
        return batch;
    }
}
