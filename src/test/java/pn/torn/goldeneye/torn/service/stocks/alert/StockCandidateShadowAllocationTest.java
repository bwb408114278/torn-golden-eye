package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBuyStrategyEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCandidateAllocationResultEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockMarketBar15mDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockSignalEventDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockCandidateTrackAllocationService.CandidateAcceptanceTarget;
import pn.torn.goldeneye.torn.service.stocks.alert.StockMarketRoundLoader.RoundSnapshot;
import pn.torn.goldeneye.torn.service.stocks.alert.policy.CandidateInfo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 候选影子接纳目标领域测试。
 * <p>
 * 验证SHADOW模式独立5槽候选影子账本的候选接纳:
 * <ul>
 *   <li>同轮6候选: 候选影子仅接纳前5个,第6个记录NO_AVAILABLE_SLOT;</li>
 *   <li>新建候选影子批次账本类型为SHADOW_FORMAL_CANDIDATE、占用VIP_SHADOW_CANDIDATE槽位;</li>
 *   <li>候选影子接纳不会触碰任何VIP_FORMAL槽位。</li>
 * </ul>
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.08.08
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("候选影子接纳目标领域测试")
class StockCandidateShadowAllocationTest {

    @Mock
    private TornStockVirtualBatchDAO virtualBatchDao;
    @Mock
    private StockShadowTrackRecorder shadowTrackRecorder;

    @Test
    @DisplayName("同轮6候选_候选影子仅接纳前5个且第6个NO_AVAILABLE_SLOT")
    void acceptCandidates_sixCandidates_topFiveAllocatedSixthNoSlot() {
        LocalDateTime roundTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        List<TornStockPortfolioSlotDO> candidateSlots = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> candidateSlot((long) i, i))
                .toList();
        List<TornStockPortfolioSlotDO> formalSlots = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> formalSlot((long) (100 + i), i))
                .toList();
        List<TornStockPortfolioSlotDO> merged = new ArrayList<>(formalSlots);
        merged.addAll(candidateSlots);

        List<CandidateInfo> candidates = IntStream.rangeClosed(1, 6)
                .mapToObj(i -> new CandidateInfo(i, "T" + i, StockBuyStrategyEnum.RANGE_LOWER_BUY,
                        List.of(StockBuyStrategyEnum.RANGE_LOWER_BUY.getCode()), BigDecimal.ONE))
                .toList();
        RoundSnapshot snapshot = new RoundSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), merged, roundTime);

        Map<Integer, TornStockMarketBar15mDO> barByStock = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            TornStockMarketBar15mDO bar = new TornStockMarketBar15mDO();
            bar.setStocksId(i);
            bar.setBarStartTime(roundTime);
            bar.setLastPrice(new BigDecimal("100.00"));
            barByStock.put(i, bar);
        }

        AtomicLong idSeq = new AtomicLong(1);
        doAnswer(inv -> {
            TornStockVirtualBatchDO batch = inv.getArgument(0);
            batch.setId(idSeq.getAndIncrement());
            return true;
        }).when(virtualBatchDao).save(any());
        when(shadowTrackRecorder.recordTrackSignalEvent(any(), anyInt(), eq(roundTime), anyString()))
                .thenAnswer(inv -> {
                    TornStockSignalEventDO event = new TornStockSignalEventDO();
                    event.setId(idSeq.getAndIncrement());
                    return event;
                });

        StockCandidateAllocationResult result = buildService().acceptCandidates(
                candidates, snapshot, barByStock, Map.of(), Map.of(), roundTime,
                CandidateAcceptanceTarget.candidateShadow());

        assertEquals(5, result.allocatedBatches().size(), "候选影子应仅接纳前5个");
        assertEquals(6, result.resultByStockId().size(), "6个候选都应有接纳结果");
        assertEquals(StockCandidateAllocationResultEnum.SHADOW_CANDIDATE_ALLOCATED,
                result.resultByStockId().get(1), "第1名应分配候选影子槽位");
        assertEquals(StockCandidateAllocationResultEnum.NO_AVAILABLE_SLOT,
                result.resultByStockId().get(6), "第6名应记录NO_AVAILABLE_SLOT");
        for (TornStockVirtualBatchDO batch : result.allocatedBatches()) {
            assertEquals(StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode(), batch.getLedgerType(),
                    "新建批次账本类型必须为SHADOW_FORMAL_CANDIDATE");
            assertEquals("VIP_SHADOW_CANDIDATE",
                    merged.stream().filter(s -> s.getId().equals(batch.getSlotId()))
                            .findFirst().orElseThrow().getPortfolioCode(),
                    "新建批次必须占用VIP_SHADOW_CANDIDATE槽位");
        }
    }

    /**
     * 构建候选影子槽位。
     *
     * @param id     槽位ID
     * @param slotNo 槽位序号
     * @return 候选影子槽位
     */
    private TornStockPortfolioSlotDO candidateSlot(Long id, int slotNo) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId(id);
        slot.setPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE);
        slot.setSlotNo(slotNo);
        slot.setInitialCash(StockPortfolioService.INITIAL_CASH);
        slot.setAvailableCash(StockPortfolioService.INITIAL_CASH);
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setSlotStatus(StockSlotStatusEnum.AVAILABLE.getCode());
        return slot;
    }

    /**
     * 构建正式组合槽位。
     *
     * @param id     槽位ID
     * @param slotNo 槽位序号
     * @return 正式槽位
     */
    private TornStockPortfolioSlotDO formalSlot(Long id, int slotNo) {
        TornStockPortfolioSlotDO slot = candidateSlot(id, slotNo);
        slot.setPortfolioCode(StockPortfolioService.PORTFOLIO_CODE);
        return slot;
    }

    /**
     * 构建候选影子目标接纳服务(仅测试槽位分配,信号评估不参与)。
     *
     * @return 接纳服务
     */
    private StockCandidateTrackAllocationService buildService() {
        return new StockCandidateTrackAllocationService(
                virtualBatchDao, new StockPortfolioService(), shadowTrackRecorder);
    }
}
