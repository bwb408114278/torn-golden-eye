package pn.torn.goldeneye.torn.service.stocks.alert.portfolio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockBatchStatusEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockLedgerTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockVirtualBatchDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
 * 通过 Mockito mock {@link TornStockPortfolioSlotDAO},使用 ArgumentCaptor 验证冲突安全批量插入的槽位字段。
 *
 * @author Bai
 * @version 1.6.1
 * @since 2026.07.25
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("股票组合初始化服务测试")
class StockPortfolioInitServiceTest {

    @Mock
    private TornStockPortfolioSlotDAO portfolioSlotDao;
    @Mock
    private TornStockVirtualBatchDAO virtualBatchDao;

    @InjectMocks
    private StockPortfolioInitService portfolioInitService;

    @BeforeEach
    void setUp() {
        lenient().when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(buildVipAlphaSlot(1)));
    }

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
        verify(portfolioSlotDao, never()).insertSlotsIgnoreConflict(any());
    }

    @ParameterizedTest(name = "平仓{0}后的复利资金应允许通过启动校验")
    @MethodSource("compoundedAvailableCashValues")
    @DisplayName("验证并初始化槽位_ 平仓盈亏后的空闲槽位可用资金偏离初始资金时返回true")
    void verifyAndInitSlots_availableSlotWithCompoundedBalance_returnsTrue(String settlementResult,
                                                                           BigDecimal availableCash) {
        List<TornStockPortfolioSlotDO> candidateSlots = new java.util.ArrayList<>(
                IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildCandidateSlot)
                        .toList());
        TornStockPortfolioSlotDO compoundedSlot = candidateSlots.getFirst();
        compoundedSlot.setAvailableCash(availableCash);

        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildFormalSlot).toList());
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE))
                .thenReturn(candidateSlots);

        boolean result = portfolioInitService.verifyAndInitSlots();

        assertTrue(result, "槽内复利后的AVAILABLE槽位不应再以initialCash作为动态现金基准: " + settlementResult);
        verifyNoInteractions(virtualBatchDao);
    }

    @Test
    @DisplayName("验证并初始化槽位_ 复利资金预留且绑定待入场候选影子批次时返回true")
    void verifyAndInitSlots_reservedSlotMatchesEntryPendingBatch_returnsTrue() {
        List<TornStockPortfolioSlotDO> candidateSlots = new java.util.ArrayList<>(
                IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildCandidateSlot)
                        .toList());
        TornStockPortfolioSlotDO reservedSlot = candidateSlots.getFirst();
        reservedSlot.setAvailableCash(BigDecimal.ZERO);
        reservedSlot.setReservedCash(new BigDecimal("2000000000.02"));
        reservedSlot.setCurrentBatchId(1002L);
        reservedSlot.setSlotStatus(StockSlotStatusEnum.RESERVED.getCode());

        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildFormalSlot).toList());
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE))
                .thenReturn(candidateSlots);
        when(virtualBatchDao.listByIds(List.of(1002L)))
                .thenReturn(List.of(buildCandidateEntryPendingBatch(reservedSlot)));

        boolean result = portfolioInitService.verifyAndInitSlots();

        assertTrue(result, "复利后的RESERVED槽位应按ENTRY_PENDING批次关联校验，不应回退到initialCash比较");
        verify(virtualBatchDao).listByIds(List.of(1002L));
    }

    @ParameterizedTest(name = "预留槽位异常[{0}]应拒绝启动期新入场")
    @MethodSource("invalidReservedSlotCases")
    @DisplayName("验证并初始化槽位_ 预留槽位的批次关联或资金异常时返回false")
    void verifyAndInitSlots_reservedSlotInvalid_returnsFalse(String scenario,
                                                             Consumer<TornStockPortfolioSlotDO> slotMutator,
                                                             Consumer<TornStockVirtualBatchDO> batchMutator,
                                                             boolean batchExists) {
        List<TornStockPortfolioSlotDO> candidateSlots = new java.util.ArrayList<>(
                IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildCandidateSlot)
                        .toList());
        TornStockPortfolioSlotDO reservedSlot = candidateSlots.getFirst();
        reservedSlot.setAvailableCash(BigDecimal.ZERO);
        reservedSlot.setReservedCash(new BigDecimal("2000000000.02"));
        reservedSlot.setCurrentBatchId(1003L);
        reservedSlot.setSlotStatus(StockSlotStatusEnum.RESERVED.getCode());
        TornStockVirtualBatchDO batch = buildCandidateEntryPendingBatch(reservedSlot);
        slotMutator.accept(reservedSlot);
        batchMutator.accept(batch);

        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildFormalSlot).toList());
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE))
                .thenReturn(candidateSlots);
        when(virtualBatchDao.listByIds(List.of(1003L)))
                .thenReturn(batchExists ? List.of(batch) : List.of());

        boolean result = portfolioInitService.verifyAndInitSlots();

        assertFalse(result, "预留槽位异常应拒绝启动期新入场: " + scenario);
    }

    @Test
    @DisplayName("验证并初始化槽位_ 候选影子已持仓且余款与批次一致时返回true")
    void verifyAndInitSlots_candidateOccupiedSlotMatchesBatch_returnsTrue() {
        TornStockPortfolioSlotDO occupiedCandidateSlot = buildCandidateSlot(1);
        occupiedCandidateSlot.setAvailableCash(new BigDecimal("667.37"));
        occupiedCandidateSlot.setCurrentBatchId(1001L);
        occupiedCandidateSlot.setSlotStatus(StockSlotStatusEnum.OCCUPIED.getCode());
        List<TornStockPortfolioSlotDO> candidateSlots = new java.util.ArrayList<>(
                IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildCandidateSlot)
                        .toList());
        candidateSlots.set(0, occupiedCandidateSlot);

        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildFormalSlot).toList());
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE))
                .thenReturn(candidateSlots);
        when(virtualBatchDao.listByIds(List.of(1001L)))
                .thenReturn(List.of(buildCandidateOpenBatch(occupiedCandidateSlot)));

        boolean result = portfolioInitService.verifyAndInitSlots();

        assertTrue(result, "已持仓候选影子槽位应按批次余款校验，不应误报现金不足");
        verify(portfolioSlotDao, never()).insertSlotsIgnoreConflict(any());
        verify(virtualBatchDao).listByIds(List.of(1001L));
    }

    @Test
    @DisplayName("验证并初始化槽位_ 正式组合槽位全部缺失,创建5个标准槽位并返回false")
    void verifyAndInitSlots_formalMissing_createsFiveStandardSlotsAndReturnsFalse() {
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(List.of(), IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildFormalSlot).toList());
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE))
                .thenReturn(IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildCandidateSlot).toList());
        when(portfolioSlotDao.insertSlotsIgnoreConflict(any())).thenReturn(StockPortfolioService.SLOT_COUNT);

        boolean result = portfolioInitService.verifyAndInitSlots();

        assertFalse(result, "任一组合槽位全部缺失时应返回false");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TornStockPortfolioSlotDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(portfolioSlotDao).insertSlotsIgnoreConflict(captor.capture());
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
                .thenReturn(List.of(slot1, slot3, slot5),
                        IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                                .mapToObj(this::buildCandidateSlot).toList());
        when(portfolioSlotDao.insertSlotsIgnoreConflict(any())).thenReturn(1);

        boolean result = portfolioInitService.verifyAndInitSlots();

        assertFalse(result, "候选影子组合槽位数量不足补建后应返回false");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TornStockPortfolioSlotDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(portfolioSlotDao, times(2)).insertSlotsIgnoreConflict(captor.capture());
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

    @Test
    @DisplayName("验证并初始化槽位_ 补建后重新查询仍缺失槽位,返回false且不宣称成功")
    void verifyAndInitSlots_repairFailsToConverge_returnsFalse() {
        List<TornStockPortfolioSlotDO> incompleteCandidate =
                List.of(buildCandidateSlot(1), buildCandidateSlot(2), buildCandidateSlot(3), buildCandidateSlot(4));
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildFormalSlot).toList());
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE))
                .thenReturn(incompleteCandidate);
        when(portfolioSlotDao.insertSlotsIgnoreConflict(any())).thenReturn(1);

        boolean result = portfolioInitService.verifyAndInitSlots();

        assertFalse(result, "补建后仍缺槽位时应返回false(fail-closed)");
        verify(portfolioSlotDao).insertSlotsIgnoreConflict(any());
    }

    @Test
    @DisplayName("验证并初始化槽位_ VIP_ALPHA缺失时创建1个100亿槽位")
    void verifyAndInitSlots_vipAlphaMissing_createsOneSlotWith10Billion() {
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.PORTFOLIO_CODE))
                .thenReturn(IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildFormalSlot).toList());
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.SHADOW_CANDIDATE_PORTFOLIO_CODE))
                .thenReturn(IntStream.rangeClosed(1, StockPortfolioService.SLOT_COUNT)
                        .mapToObj(this::buildCandidateSlot).toList());
        when(portfolioSlotDao.selectAllByPortfolioCode(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE))
                .thenReturn(List.of(), List.of(buildVipAlphaSlot(1)));
        when(portfolioSlotDao.insertSlotsIgnoreConflict(any())).thenReturn(1);

        assertFalse(portfolioInitService.verifyAndInitSlots());
        ArgumentCaptor<List<TornStockPortfolioSlotDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(portfolioSlotDao, times(1)).insertSlotsIgnoreConflict(captor.capture());
        TornStockPortfolioSlotDO slot = captor.getValue().getFirst();
        assertEquals(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, slot.getPortfolioCode());
        assertEquals(1, slot.getSlotNo());
        assertEquals(0, StockPortfolioService.VIP_ALPHA_INITIAL_CASH.compareTo(slot.getInitialCash()));
        assertEquals(0, StockPortfolioService.VIP_ALPHA_INITIAL_CASH.compareTo(slot.getAvailableCash()));
    }


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

    private TornStockPortfolioSlotDO buildVipAlphaSlot(int slotNo) {
        TornStockPortfolioSlotDO slot = buildStandardSlot(StockPortfolioService.VIP_ALPHA_PORTFOLIO_CODE, slotNo);
        slot.setInitialCash(StockPortfolioService.VIP_ALPHA_INITIAL_CASH);
        slot.setAvailableCash(StockPortfolioService.VIP_ALPHA_INITIAL_CASH);
        return slot;
    }

    /**
     * 构建指定组合的标准初始槽位。
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
     * 构建与已占用候选影子槽位绑定的开放批次。
     *
     * @param slot 已占用候选影子槽位
     * @return 槽位账本一致的开放候选影子批次
     */
    private TornStockVirtualBatchDO buildCandidateOpenBatch(TornStockPortfolioSlotDO slot) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(slot.getCurrentBatchId());
        batch.setDeleted(0);
        batch.setLedgerType(StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode());
        batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode());
        batch.setSlotId(slot.getId());
        batch.setSlotNo(slot.getSlotNo());
        batch.setRemainingCash(slot.getAvailableCash());
        return batch;
    }

    /**
     * 构建与已预留候选影子槽位绑定的待入场批次。
     *
     * @param slot 已预留候选影子槽位
     * @return 槽位账本一致的待入场候选影子批次
     */
    private TornStockVirtualBatchDO buildCandidateEntryPendingBatch(TornStockPortfolioSlotDO slot) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(slot.getCurrentBatchId());
        batch.setDeleted(0);
        batch.setLedgerType(StockLedgerTypeEnum.SHADOW_FORMAL_CANDIDATE.getCode());
        batch.setBatchStatus(StockBatchStatusEnum.ENTRY_PENDING.getCode());
        batch.setSlotId(slot.getId());
        batch.setSlotNo(slot.getSlotNo());
        return batch;
    }

    /**
     * 提供预留槽位启动校验应拒绝的最小异常场景。
     *
     * @return 场景名、槽位变更、批次变更及批次是否存在组成的参数流
     */
    private static Stream<Arguments> invalidReservedSlotCases() {
        return Stream.of(
                Arguments.of("关联批次缺失",
                        (Consumer<TornStockPortfolioSlotDO>) slot -> {
                        },
                        (Consumer<TornStockVirtualBatchDO>) batch -> {
                        }, false),
                Arguments.of("账本类型不匹配",
                        (Consumer<TornStockPortfolioSlotDO>) slot -> {
                        },
                        (Consumer<TornStockVirtualBatchDO>) batch ->
                                batch.setLedgerType(StockLedgerTypeEnum.FORMAL.getCode()), true),
                Arguments.of("批次不是待入场状态",
                        (Consumer<TornStockPortfolioSlotDO>) slot -> {
                        },
                        (Consumer<TornStockVirtualBatchDO>) batch ->
                                batch.setBatchStatus(StockBatchStatusEnum.OPEN.getCode()), true),
                Arguments.of("槽位编号不匹配",
                        (Consumer<TornStockPortfolioSlotDO>) slot -> {
                        },
                        (Consumer<TornStockVirtualBatchDO>) batch -> batch.setSlotNo(2), true),
                Arguments.of("槽位ID不匹配",
                        (Consumer<TornStockPortfolioSlotDO>) slot -> {
                        },
                        (Consumer<TornStockVirtualBatchDO>) batch -> batch.setSlotId(2L), true),
                Arguments.of("批次已逻辑删除",
                        (Consumer<TornStockPortfolioSlotDO>) slot -> {
                        },
                        (Consumer<TornStockVirtualBatchDO>) batch -> batch.setDeleted(1), true),
                Arguments.of("预留资金为零",
                        (Consumer<TornStockPortfolioSlotDO>) slot -> slot.setReservedCash(BigDecimal.ZERO),
                        (Consumer<TornStockVirtualBatchDO>) batch -> {
                        }, true)
        );
    }

    /**
     * 提供槽内复利后可合法偏离初始资金的空闲槽位余额。
     *
     * @return 平仓结果和可用资金组成的参数流
     */
    private static Stream<Arguments> compoundedAvailableCashValues() {
        return Stream.of(
                Arguments.of("盈利", new BigDecimal("2000000000.02")),
                Arguments.of("亏损", new BigDecimal("1999999999.98"))
        );
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
