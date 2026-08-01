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
 * @version 1.2.12
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
                99L, 9999, StockLedgerTypeEnum.FORMAL.getCode(), 1L, roundTime);
        List<TornStockPortfolioSlotDO> lockedSlots = buildFiveFormalSlots(formalExitPendingBatch);
        // 事务外快照: 合法5槽形状(slotNo 1~5),槽1被陈旧外部批次占用,
        // 事务内会被锁后数据替换,证明编排不信任事务外快照。
        List<TornStockPortfolioSlotDO> externalSlots = buildFiveFormalSlots(externalSnapshotBatch);
        List<CandidateInfo> candidates = List.of(candidate(1001), candidate(1002));
        RoundSnapshot snapshot = new RoundSnapshot(
                List.of(usableBar(1001, roundTime), usableBar(1002, roundTime)), List.of(), List.of(),
                List.of(externalSnapshotBatch), List.of(), List.of(), externalSlots, roundTime);

        stubRoundExecution(roundTime, formalExitPendingBatch, shadowExitPendingBatch, lockedSlots, candidates);

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
        assertSlotSetCompleteness(capturedSnapshot.slots(), lockedSlots);
        assertSlotSettlement(capturedSnapshot.slots());
    }

    /**
     * 配置本测试所需的轮次事务协作者返回值。
     *
     * @param roundTime              轮次时间
     * @param formalExitPendingBatch 事务内锁定的待卖出正式批次
     * @param shadowExitPendingBatch 事务内锁定的待卖出影子批次
     * @param lockedSlots            事务内锁定的完整5槽正式槽位列表
     * @param candidates             评估得到的正式候选
     */
    private void stubRoundExecution(LocalDateTime roundTime,
                                    TornStockVirtualBatchDO formalExitPendingBatch,
                                    TornStockVirtualBatchDO shadowExitPendingBatch,
                                    List<TornStockPortfolioSlotDO> lockedSlots,
                                    List<CandidateInfo> candidates) {
        TornStockMarketRoundDO round = new TornStockMarketRoundDO();
        when(marketRoundDao.selectByRoundTimeForUpdate(roundTime)).thenReturn(round);
        when(portfolioSlotDao.selectAllByPortfolioCodeForUpdate(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(lockedSlots);
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

    /**
     * 构建生产形状的5槽正式组合: 1个OCCUPIED槽位关联正式批次, 其余4个AVAILABLE槽位。
     * <p>
     * 冻结设计正式组合固定为5槽, 每槽初始资金 {@link StockPortfolioService#INITIAL_CASH}。
     * 占用槽位携带建仓后真实余款, 用于验证平仓结算仅释放占用槽位而其余槽位不变。
     *
     * @param occupiedBatch 关联到1号占用槽位的正式批次
     * @return 5个字段合法的正式组合槽位
     */
    private List<TornStockPortfolioSlotDO> buildFiveFormalSlots(TornStockVirtualBatchDO occupiedBatch) {
        TornStockPortfolioSlotDO occupied = formalSlot(1L, StockSlotStatusEnum.OCCUPIED, occupiedBatch.getId());
        occupied.setAvailableCash(new BigDecimal("1999990000.00"));
        return List.of(
                occupied,
                formalSlot(2L, StockSlotStatusEnum.AVAILABLE, null),
                formalSlot(3L, StockSlotStatusEnum.AVAILABLE, null),
                formalSlot(4L, StockSlotStatusEnum.AVAILABLE, null),
                formalSlot(5L, StockSlotStatusEnum.AVAILABLE, null));
    }

    /**
     * 构建字段合法的正式组合槽位。
     *
     * @param id             槽位ID
     * @param status         槽位状态
     * @param currentBatchId 当前批次ID(空仓为null)
     * @return 槽位DO
     */
    private TornStockPortfolioSlotDO formalSlot(Long id, StockSlotStatusEnum status, Long currentBatchId) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(id);
        slot.setPortfolioCode(StockPortfolioService.PORTFOLIO_CODE);
        slot.setSlotNo(id.intValue());
        slot.setInitialCash(StockPortfolioService.INITIAL_CASH);
        slot.setAvailableCash(StockPortfolioService.INITIAL_CASH);
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setCurrentBatchId(currentBatchId);
        slot.setSlotStatus(status.getCode());
        slot.setLockVersion(1L);
        return slot;
    }

    /**
     * 断言锁后槽位列表完整性与对象一致性。
     * <p>
     * 锁后列表必须恰好5槽、ID与slotNo均为1~5且无重复、顺序正确,
     * 且每个元素与锁查询返回对象逐槽 assertSame,证明事务使用锁后对象而非事务外快照。
     *
     * @param slots       锁后传入候选接纳的槽位列表
     * @param lockedSlots 锁查询返回的槽位列表
     */
    private void assertSlotSetCompleteness(List<TornStockPortfolioSlotDO> slots,
                                           List<TornStockPortfolioSlotDO> lockedSlots) {
        assertEquals(5, slots.size(), "锁后槽位列表应为5槽");
        List<Long> ids = slots.stream().map(TornStockPortfolioSlotDO::getId).toList();
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids, "槽位ID应恰好为1~5且无重复");
        List<Integer> slotNos = slots.stream().map(TornStockPortfolioSlotDO::getSlotNo).toList();
        assertEquals(List.of(1, 2, 3, 4, 5), slotNos, "slotNo应恰好为1~5且无重复顺序正确");
        assertEquals(5, ids.stream().distinct().count(), "槽位ID不应重复");
        for (int i = 0; i < slots.size(); i++) {
            assertSame(lockedSlots.get(i), slots.get(i), "锁后槽位应为锁查询返回对象");
        }
    }

    /**
     * 断言平仓后槽位结算形状: 1号占用槽位已释放为AVAILABLE并回笼卖出所得,
     * 其余4个AVAILABLE槽位字段保持不变。
     *
     * @param slots 锁后传入候选接纳的槽位列表
     */
    private void assertSlotSettlement(List<TornStockPortfolioSlotDO> slots) {
        TornStockPortfolioSlotDO settledSlot = slots.stream()
                .filter(slot -> slot.getId() == 1L)
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少1号占用槽位"));
        assertEquals(StockSlotStatusEnum.AVAILABLE.getCode(), settledSlot.getSlotStatus());
        assertNull(settledSlot.getCurrentBatchId());
        assertEquals(0, new BigDecimal("2000000089.90").compareTo(settledSlot.getAvailableCash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(settledSlot.getReservedCash()));
        assertEquals(0, StockPortfolioService.INITIAL_CASH.compareTo(settledSlot.getInitialCash()));
        assertEquals(1L, settledSlot.getLockVersion());
        assertEquals(StockPortfolioService.PORTFOLIO_CODE, settledSlot.getPortfolioCode());
        assertEquals(1, settledSlot.getSlotNo());

        slots.stream()
                .filter(slot -> slot.getId() != 1L)
                .forEach(slot -> {
                    assertEquals(StockSlotStatusEnum.AVAILABLE.getCode(), slot.getSlotStatus());
                    assertNull(slot.getCurrentBatchId());
                    assertEquals(0, StockPortfolioService.INITIAL_CASH.compareTo(slot.getAvailableCash()));
                    assertEquals(0, BigDecimal.ZERO.compareTo(slot.getReservedCash()));
                    assertEquals(0, StockPortfolioService.INITIAL_CASH.compareTo(slot.getInitialCash()));
                    assertEquals(1L, slot.getLockVersion());
                    assertEquals(StockPortfolioService.PORTFOLIO_CODE, slot.getPortfolioCode());
                    assertEquals(slot.getId().intValue(), slot.getSlotNo(), "slotNo应等于槽位ID");
                });
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
