package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 股票组合初始化服务单元测试 - 覆盖正式与候选影子两个组合的槽位完整性校验、缺失补建与金额合理性验证
 * <p>
 * 验证 {@link StockPortfolioInitService} 的核心规则:
 * <ul>
 *   <li>两个组合槽位完整且金额正确时返回true,不触发任何写操作</li>
 *   <li>任一组合槽位全部缺失时一次性创建5个标准初始槽位并返回false</li>
 *   <li>任一组合槽位数量不足时按缺失序号补建并返回false</li>
 *   <li>{@code getSlotCount} 正确返回指定组合槽位数,空集合返回0</li>
 * </ul>
 * 通过 Mockito mock {@link TornStockPortfolioSlotDAO},使用 ArgumentCaptor 验证批量保存的槽位字段。
 *
 * @author Bai
 * @version 1.2.14
 * @since 2026.07.25
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("股票组合初始化服务测试")
class StockPortfolioInitServiceTest {

    @Mock
    private TornStockPortfolioSlotDAO portfolioSlotDao;

    @InjectMocks
    private StockPortfolioInitService portfolioInitService;

    // ==================== verifyAndInitSlots ====================

    @Test
    @DisplayName("验证并初始化槽位_ 两个组合槽位完整且金额正确,返回true且不触发写操作")
    void verifyAndInitSlots_slotsCompleteAndAmountCorrect_returnsTrue() {
        List<TornStockPortfolioSlotDO> formalSlots = IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                .mapToObj(this::buildFormalSlot)
                .toList();
        List<TornStockPortfolioSlotDO> candidateSlots = IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                .mapToObj(this::buildCandidateSlot)
                .toList();
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(formalSlots);
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE))
                .thenReturn(candidateSlots);

        boolean result = portfolioInitService.verifyAndInitSlots();

        assertTrue(result, "两个组合槽位完整且金额正确时应返回true");
        verify(portfolioSlotDao, never()).saveBatch(any());
    }

    @Test
    @DisplayName("验证并初始化槽位_ 正式组合槽位全部缺失,创建5个标准槽位并返回false")
    void verifyAndInitSlots_formalMissing_createsFiveStandardSlotsAndReturnsFalse() {
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(List.of());
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE))
                .thenReturn(IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildCandidateSlot).toList());

        boolean result = portfolioInitService.verifyAndInitSlots();

        assertFalse(result, "任一组合槽位全部缺失时应返回false");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TornStockPortfolioSlotDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(portfolioSlotDao).saveBatch(captor.capture());
        List<TornStockPortfolioSlotDO> saved = captor.getValue();
        assertEquals(StockPortfolioService.SLOT_COUNT, saved.size(), "应创建5个标准槽位");
        List<Integer> savedSlotNos = saved.stream().map(TornStockPortfolioSlotDO::getSlotNo).sorted().toList();
        assertEquals(List.of(1, 2, 3, 4, 5), savedSlotNos, "槽位序号应为1~5");
        for (TornStockPortfolioSlotDO slot : saved) {
            assertEquals(StockPortfolioService.PORTFOLIO_CODE, slot.getPortfolioCode(),
                    "正式组合缺失时应创建VIP_FORMAL槽位");
            assertStandardSlotFields(slot);
        }
    }

    @Test
    @DisplayName("验证并初始化槽位_ 候选影子组合槽位数量不足,补建缺失序号槽位并返回false")
    void verifyAndInitSlots_candidateInsufficient_supplementsMissingSlotsAndReturnsFalse() {
        TornStockPortfolioSlotDO slot1 = buildCandidateSlot(1);
        TornStockPortfolioSlotDO slot3 = buildCandidateSlot(3);
        TornStockPortfolioSlotDO slot5 = buildCandidateSlot(5);
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildFormalSlot).toList());
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE))
                .thenReturn(List.of(slot1, slot3, slot5));

        boolean result = portfolioInitService.verifyAndInitSlots();

        assertFalse(result, "候选影子组合槽位数量不足补建后应返回false");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TornStockPortfolioSlotDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(portfolioSlotDao, times(2)).saveBatch(captor.capture());
        List<TornStockPortfolioSlotDO> supplemented = captor.getAllValues().stream()
                .flatMap(List::stream)
                .toList();
        assertEquals(2, supplemented.size(), "应补建2个缺失槽位");
        List<Integer> supplementedSlotNos = supplemented.stream()
                .map(TornStockPortfolioSlotDO::getSlotNo)
                .sorted()
                .toList();
        assertEquals(List.of(2, 4), supplementedSlotNos, "应补建序号2和4的槽位");
        for (TornStockPortfolioSlotDO slot : supplemented) {
            assertEquals(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE, slot.getPortfolioCode(),
                    "补建槽位组合编码应为VIP_SHADOW_CANDIDATE");
            assertStandardSlotFields(slot);
        }
    }

    // ==================== getSlotCount ====================

    @Test
    @DisplayName("获取槽位数量_ 正常查询5个槽位,返回正确数量")
    void getSlotCount_normalQuery_returnsCorrectCount() {
        List<TornStockPortfolioSlotDO> slots = IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                .mapToObj(this::buildFormalSlot)
                .toList();
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(slots);

        int count = portfolioInitService.getSlotCount();

        assertEquals(StockPortfolioService.SLOT_COUNT, count, "应返回5个槽位");
    }

    @Test
    @DisplayName("获取槽位数量_ 槽位不存在时返回0")
    void getSlotCount_noSlots_returnsZero() {
        when(portfolioSlotDao.selectAllByPortfolioCode(anyString()))
                .thenReturn(List.of());

        int count = portfolioInitService.getSlotCount();

        assertEquals(0, count, "无槽位时应返回0");
    }

    @Test
    @DisplayName("获取槽位数量_ 指定候选影子组合返回正确数量")
    void getSlotCount_candidatePortfolio_returnsCount() {
        List<TornStockPortfolioSlotDO> slots = IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                .mapToObj(this::buildCandidateSlot)
                .toList();
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE))
                .thenReturn(slots);

        int count = portfolioInitService.getSlotCount(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE);

        assertEquals(StockPortfolioService.SLOT_COUNT, count, "候选影子组合应返回5个槽位");
    }

    // ==================== Helper方法 ====================

    /**
     * 构建正式组合标准初始槽位。
     *
     * @param slotNo 槽位序号
     * @return 标准初始槽位DO
     */
    private TornStockPortfolioSlotDO buildFormalSlot(int slotNo) {
        return buildStandardSlot(StockPortfolioService.PORTFOLIO_CODE, slotNo);
    }

    /**
     * 构建候选影子组合标准初始槽位。
     *
     * @param slotNo 槽位序号
     * @return 标准初始槽位DO
     */
    private TornStockPortfolioSlotDO buildCandidateSlot(int slotNo) {
        return buildStandardSlot(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE, slotNo);
    }

    /**
     * 构建指定组合的标准初始槽位(initialCash=20亿,availableCash=20亿,reserved=0,状态AVAILABLE)
     *
     * @param portfolioCode 组合编码
     * @param slotNo        槽位序号
     * @return 标准初始槽位DO
     */
    private TornStockPortfolioSlotDO buildStandardSlot(String portfolioCode, int slotNo) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId((long) slotNo);
        slot.setPortfolioCode(portfolioCode);
        slot.setSlotNo(slotNo);
        slot.setInitialCash(StockPortfolioService.INITIAL_CASH);
        slot.setAvailableCash(StockPortfolioService.INITIAL_CASH);
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setCurrentBatchId(null);
        slot.setSlotStatus(StockSlotStatusEnum.AVAILABLE.getCode());
        slot.setLockVersion(0L);
        return slot;
    }

    /**
     * 断言槽位字段符合标准初始槽位规范。
     *
     * @param slot 待校验槽位
     */
    private void assertStandardSlotFields(TornStockPortfolioSlotDO slot) {
        assertEquals(0, StockPortfolioService.INITIAL_CASH.compareTo(slot.getInitialCash()),
                "initialCash应为20亿,实际: " + slot.getInitialCash());
        assertEquals(0, StockPortfolioService.INITIAL_CASH.compareTo(slot.getAvailableCash()),
                "availableCash应为20亿,实际: " + slot.getAvailableCash());
        assertEquals(0, BigDecimal.ZERO.compareTo(slot.getReservedCash()),
                "reservedCash应为0,实际: " + slot.getReservedCash());
        assertNull(slot.getCurrentBatchId(), "currentBatchId应为null");
        assertEquals(StockSlotStatusEnum.AVAILABLE.getCode(), slot.getSlotStatus(),
                "slotStatus应为AVAILABLE");
        assertNotNull(slot.getLockVersion(), "lockVersion不应为null");
        assertEquals(0L, slot.getLockVersion(), "lockVersion应初始化为0");
    }
}
