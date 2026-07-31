package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.SettingConstants;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockRuleModeEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockBatchMarkDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBuySignalEvaluator.BuySignalResult;
import pn.torn.goldeneye.torn.service.stocks.alert.StockEntrySettlementService.EntrySettlementResult;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.StockCandidateRankingPolicy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 股票轮次事务编排测试，验证本轮正式平仓股票不会重新进入正式候选接纳。
 *
 * @author Bai
 * @version 1.2.10
 * @since 2026.07.17
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("股票轮次事务编排测试")
class StockRoundTransactionServiceTest {

    @Mock
    private TornStockMarketRoundDAO marketRoundDao;
    @Mock
    private TornStockVirtualBatchDAO virtualBatchDao;
    @Mock
    private TornStockPortfolioSlotDAO portfolioSlotDao;
    @Mock
    private TornStockBatchMarkDAO batchMarkDao;
    @Mock
    private StockEntrySettlementService entrySettlementService;
    @Mock
    private StockBatchPathService batchPathService;
    @Mock
    private StockBuySignalEvaluator buySignalEvaluator;
    @Mock
    private StockShadowRecordWriter shadowRecordWriter;
    @Mock
    private StockSignalStateUpdater signalStateUpdater;
    @Mock
    private SysSettingManager sysSettingManager;
    @Captor
    private ArgumentCaptor<List<CandidateInfo>> candidatesCaptor;

    private StockRoundTransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new StockRoundTransactionService(
                marketRoundDao, virtualBatchDao, portfolioSlotDao, batchMarkDao,
                entrySettlementService, batchPathService, buySignalEvaluator,
                new StockCandidateRankingPolicy(), shadowRecordWriter, signalStateUpdater, sysSettingManager);
    }

    @Test
    @DisplayName("同轮正式平仓股票_候选接纳前必须排除且影子平仓不影响其他候选")
    void executeRound_formalExitExcludesSameStockBeforeCandidateAcceptance() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 7, 31, 10, 0);
        TornStockVirtualBatchDO formalClosedBatch = closedBatch(1001, StockLedgerTypeEnum.FORMAL.getCode());
        TornStockVirtualBatchDO shadowClosedBatch = closedBatch(1002, StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode());
        List<CandidateInfo> candidates = List.of(candidate(1001), candidate(1002));
        RoundSnapshot snapshot = new RoundSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), roundTime);

        stubRoundExecution(roundTime, formalClosedBatch, shadowClosedBatch, candidates);

        transactionService.executeRound(roundTime, snapshot);

        verify(buySignalEvaluator).acceptCandidates(candidatesCaptor.capture(), eq(snapshot), any(), any(), any(), eq(roundTime));
        assertFalse(candidatesCaptor.getValue().stream()
                .map(CandidateInfo::stocksId)
                .toList()
                .contains(1001));
        assertEquals(List.of(1002), candidatesCaptor.getValue().stream()
                .map(CandidateInfo::stocksId)
                .toList());
    }

    /**
     * 配置本测试所需的轮次事务协作者返回值。
     *
     * @param roundTime         轮次时间
     * @param formalClosedBatch 本轮已成交正式批次
     * @param shadowClosedBatch 本轮已成交影子批次
     * @param candidates        评估得到的正式候选
     */
    private void stubRoundExecution(LocalDateTime roundTime,
                                    TornStockVirtualBatchDO formalClosedBatch,
                                    TornStockVirtualBatchDO shadowClosedBatch,
                                    List<CandidateInfo> candidates) {
        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        when(marketRoundDao.selectByRoundTimeForUpdate(roundTime)).thenReturn(round);
        when(portfolioSlotDao.selectAllByPortfolioCodeForUpdate(StockPortfolioService.PORTFOLIO_CODE)).thenReturn(List.of());
        when(virtualBatchDao.selectActiveFormalBatchesForUpdate()).thenReturn(List.of());
        when(virtualBatchDao.selectActiveShadowBatchesForUpdate()).thenReturn(List.of());
        when(entrySettlementService.processEntryPending(any(), any(), eq(roundTime)))
                .thenReturn(new EntrySettlementResult(List.of(), List.of()));
        when(entrySettlementService.processExitPending(any(), any(), eq(roundTime)))
                .thenReturn(List.of(formalClosedBatch, shadowClosedBatch));
        when(batchPathService.updatePaths(any(), any(), eq(roundTime))).thenReturn(List.of());
        when(buySignalEvaluator.evaluateSignals(any(), any(), any(), any(), eq(roundTime)))
                .thenReturn(new BuySignalResult(candidates, List.of()));
        when(sysSettingManager.getSettingValue(SettingConstants.KEY_VIP_STOCK_RULE_MODE))
                .thenReturn(StockRuleModeEnum.PROVISIONAL.getCode());
        when(buySignalEvaluator.acceptCandidates(any(), any(), any(), any(), any(), eq(roundTime)))
                .thenReturn(StockCandidateAllocationResult.empty());
    }

    /**
     * 创建候选。
     *
     * @param stocksId 股票ID
     * @return 候选信息
     */
    private CandidateInfo candidate(int stocksId) {
        return new CandidateInfo(stocksId, "T" + stocksId, null, List.of(), BigDecimal.ONE);
    }

    /**
     * 创建本轮已成交批次。
     *
     * @param stocksId   股票ID
     * @param ledgerType 账本类型
     * @return 已成交批次
     */
    private TornStockVirtualBatchDO closedBatch(int stocksId, String ledgerType) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setStocksId(stocksId);
        batch.setLedgerType(ledgerType);
        return batch;
    }
}
