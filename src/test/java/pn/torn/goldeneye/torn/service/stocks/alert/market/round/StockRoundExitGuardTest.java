package pn.torn.goldeneye.torn.service.stocks.alert.market.round;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.signal.policy.CandidateInfo;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 股票轮次平仓保护测试，验证本轮正式平仓股票不得再次进入正式候选。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@DisplayName("股票轮次平仓保护测试")
class StockRoundExitGuardTest {

    @Test
    @DisplayName("本轮正式平仓股票_不得进入新的正式候选")
    void excludeFormalExitStocks_excludesOnlyStocksClosedInTheSameRound() {
        TornStockVirtualBatchDO formalClosedBatch = new TornStockVirtualBatchDO();
        formalClosedBatch.setStocksId(1001);
        formalClosedBatch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode());

        TornStockVirtualBatchDO shadowClosedBatch = new TornStockVirtualBatchDO();
        shadowClosedBatch.setStocksId(1002);
        shadowClosedBatch.setLedgerType(StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());

        List<CandidateInfo> candidates = List.of(
                candidate(1001),
                candidate(1002),
                candidate(1003)
        );

        List<CandidateInfo> eligibleCandidates = StockRoundExitGuard.excludeFormalExitStocks(
                candidates, List.of(formalClosedBatch, shadowClosedBatch));

        assertEquals(List.of(1002, 1003), eligibleCandidates.stream()
                .map(CandidateInfo::stocksId)
                .toList());
    }

    /**
     * 构建候选。
     *
     * @param stocksId 股票ID
     * @return 候选信息
     */
    private CandidateInfo candidate(int stocksId) {
        return new CandidateInfo(stocksId, "T" + stocksId, null, List.of(), BigDecimal.ONE);
    }
}
