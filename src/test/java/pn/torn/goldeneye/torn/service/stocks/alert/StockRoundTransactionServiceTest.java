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
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.*;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockBatchMarkDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockMarketRoundDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketRoundDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.manager.setting.SysSettingManager;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBuySignalEvaluator.BuySignalResult;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.StockCandidateRankingPolicy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
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
    @Captor
    private ArgumentCaptor<RoundSnapshot> snapshotCaptor;

    private StockRoundTransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new StockRoundTransactionService(
                marketRoundDao, virtualBatchDao, portfolioSlotDao, batchMarkDao,
                new StockEntrySettlementService(new StockPortfolioService()), batchPathService, buySignalEvaluator,
                new StockCandidateRankingPolicy(), shadowRecordWriter, signalStateUpdater, sysSettingManager);
    }

    @Test
    @DisplayName("同轮正式平仓股票_候选接纳前必须排除且影子平仓不影响其他候选")
    void executeRound_formalExitExcludesSameStockBeforeCandidateAcceptance() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 7, 31, 10, 0);
        TornStockVirtualBatchDO formalExitPendingBatch = exitPendingBatch(
                11L, 1001, StockLedgerTypeEnum.FORMAL.getCode(), 1L, roundTime);
        TornStockVirtualBatchDO shadowExitPendingBatch = exitPendingBatch(
                12L, 1002, StockLedgerTypeEnum.UNLIMITED_SHADOW.getCode(), null, roundTime);
        TornStockVirtualBatchDO externalSnapshotBatch = exitPendingBatch(
                99L, 9999, StockLedgerTypeEnum.FORMAL.getCode(), 9L, roundTime);
        TornStockPortfolioSlotDO lockedSlot = occupiedSlot(1L, formalExitPendingBatch.getId());
        TornStockPortfolioSlotDO externalSnapshotSlot = occupiedSlot(9L, externalSnapshotBatch.getId());
        List<CandidateInfo> candidates = List.of(candidate(1001), candidate(1002));
        RoundSnapshot snapshot = new RoundSnapshot(
                List.of(usableBar(1001, roundTime), usableBar(1002, roundTime)), List.of(), List.of(),
                List.of(externalSnapshotBatch), List.of(), List.of(), List.of(externalSnapshotSlot), roundTime);

        stubRoundExecution(roundTime, formalExitPendingBatch, shadowExitPendingBatch, lockedSlot, candidates);

        transactionService.executeRound(roundTime, snapshot);

        verify(buySignalEvaluator).acceptCandidates(
                candidatesCaptor.capture(), snapshotCaptor.capture(), any(), any(), any(), eq(roundTime));
        assertFalse(candidatesCaptor.getValue().stream()
                .map(CandidateInfo::stocksId)
                .toList()
                .contains(1001));
        assertEquals(List.of(1002), candidatesCaptor.getValue().stream()
                .map(CandidateInfo::stocksId)
                .toList());
        assertEquals(StockBatchStatusEnum.CLOSED_TARGET.getCode(), formalExitPendingBatch.getBatchStatus());
        assertEquals(StockBatchStatusEnum.CLOSED_TARGET.getCode(), shadowExitPendingBatch.getBatchStatus());
        RoundSnapshot capturedSnapshot = snapshotCaptor.getValue();
        assertNotSame(snapshot, capturedSnapshot);
        assertEquals(2, capturedSnapshot.activeBatches().size());
        assertSame(formalExitPendingBatch, capturedSnapshot.activeBatches().getFirst());
        assertSame(shadowExitPendingBatch, capturedSnapshot.activeBatches().get(1));
        assertEquals(List.of(shadowExitPendingBatch), capturedSnapshot.shadowBatches());
        assertEquals(List.of(lockedSlot), capturedSnapshot.slots());
    }

    /**
     * 配置本测试所需的轮次事务协作者返回值。
     *
     * @param roundTime              轮次时间
     * @param formalExitPendingBatch 事务内锁定的待卖出正式批次
     * @param shadowExitPendingBatch 事务内锁定的待卖出影子批次
     * @param lockedSlot             事务内锁定的正式槽位
     * @param candidates             评估得到的正式候选
     */
    private void stubRoundExecution(LocalDateTime roundTime,
                                    TornStockVirtualBatchDO formalExitPendingBatch,
                                    TornStockVirtualBatchDO shadowExitPendingBatch,
                                    TornStockPortfolioSlotDO lockedSlot,
                                    List<CandidateInfo> candidates) {
        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        when(marketRoundDao.selectByRoundTimeForUpdate(roundTime)).thenReturn(round);
        when(portfolioSlotDao.selectAllByPortfolioCodeForUpdate(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(List.of(lockedSlot));
        when(virtualBatchDao.selectActiveFormalBatchesForUpdate()).thenReturn(List.of(formalExitPendingBatch));
        when(virtualBatchDao.selectActiveShadowBatchesForUpdate()).thenReturn(List.of(shadowExitPendingBatch));
        when(batchPathService.updatePathsAndEvaluateExits(any(), any(), any(), eq(roundTime))).thenReturn(List.of());
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
     * 创建由事务内待卖出结算服务实际成交的批次。
     *
     * @param id         批次ID
     * @param stocksId   股票ID
     * @param ledgerType 账本类型
     * @param slotId     正式批次关联槽位，影子批次为空
     * @param roundTime  本轮时间
     * @return 待卖出批次
     */
    private TornStockVirtualBatchDO exitPendingBatch(Long id, int stocksId, String ledgerType,
                                                     Long slotId, LocalDateTime roundTime) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(id);
        batch.setBatchNo("B" + id);
        batch.setStocksId(stocksId);
        batch.setLedgerType(ledgerType);
        batch.setBatchStatus(StockBatchStatusEnum.EXIT_PENDING.getCode());
        batch.setEntryReferencePrice(new BigDecimal("100.00"));
        batch.setEntryTime(roundTime.minusDays(1));
        batch.setQuantity(100L);
        batch.setExpectedExitBarTime(roundTime);
        batch.setExitReason(StockCloseTypeEnum.CLOSED_TARGET.getCode());
        batch.setSlotId(slotId);
        return batch;
    }

    private TornStockPortfolioSlotDO occupiedSlot(Long id, Long batchId) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(id);
        slot.setSlotNo(id.intValue());
        slot.setAvailableCash(BigDecimal.ZERO);
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setCurrentBatchId(batchId);
        slot.setSlotStatus(StockSlotStatusEnum.OCCUPIED.getCode());
        return slot;
    }

    private TornStockMarketBar15mDO usableBar(int stocksId, LocalDateTime roundTime) {
        TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
        bar.setStocksId(stocksId);
        bar.setBarStartTime(roundTime);
        bar.setBarEndTime(roundTime.plusMinutes(15));
        bar.setLastPrice(new BigDecimal("101.00"));
        bar.setSampleCount(15);
        bar.setLastSampleTime(roundTime.plusMinutes(14));
        bar.setUsable(true);
        return bar;
    }
}
