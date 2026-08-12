package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockSlotStatusEnum;
import pn.torn.goldeneye.repository.dao.torn.stocks.portfolio.TornStockPortfolioSlotDAO;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockPortfolioSlotDO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票组合管理服务单元测试 - 覆盖资金模型、槽位生命周期与纯计算方法
 * <p>
 * 验证 {@link StockPortfolioService} 的整数股数取整、卖出手续费扣除(0.1%)、
 * 槽位预留/占用/释放/结算的状态流转与金额正确性、入场价格偏离阈值(0.15%)的边界判定、
 * 入场过期时间计算以及组合权益计算。静态方法直接调用,实例方法通过 Mockito mock DAO 构造服务实例。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("股票组合管理服务测试")
class StockPortfolioServiceTest {

    @Mock
    private TornStockPortfolioSlotDAO portfolioSlotDAO;

    private StockPortfolioService portfolioService;

    @BeforeEach
    void setUp() {
        portfolioService = new StockPortfolioService();
    }

    // ==================== 纯计算方法(静态) ====================

    @Test
    @DisplayName("计算股数_20亿除以100元入场价,返回2000万股")
    void calculateQuantity_2BillionDividedBy100_returns20MillionShares() {
        BigDecimal availableCash = new BigDecimal("2000000000.00");
        BigDecimal entryReferencePrice = new BigDecimal("100.00");

        Long quantity = StockPortfolioService.calculateQuantity(availableCash, entryReferencePrice);

        assertEquals(20_000_000L, quantity);
    }

    @Test
    @DisplayName("计算股数_可用资金有余款时向下取整")
    void calculateQuantity_hasRemainder_floorDown() {
        // 9999.99 / 100 = 99.9999 -> floor = 99
        BigDecimal availableCash = new BigDecimal("9999.99");
        BigDecimal entryReferencePrice = new BigDecimal("100.00");

        Long quantity = StockPortfolioService.calculateQuantity(availableCash, entryReferencePrice);

        assertEquals(99L, quantity);
    }

    @Test
    @DisplayName("净收益计算_买入100卖出100,扣除0.1%手续费后净收益为-0.1%")
    void calculateNetReturn_buy100Sell100_after0p1FeeIsMinus0p1() {
        BigDecimal entryReferencePrice = new BigDecimal("100.00");
        BigDecimal exitReferencePrice = new BigDecimal("100.00");

        BigDecimal netReturn = StockPortfolioService.calculateNetReturn(entryReferencePrice, exitReferencePrice);

        assertNotNull(netReturn);
        // 100/100 × 0.999 - 1 = -0.001
        assertEquals(0, netReturn.compareTo(new BigDecimal("-0.001")));
    }

    @Test
    @DisplayName("净收益计算_买入100卖出101,扣除0.1%手续费后净收益为正")
    void calculateNetReturn_buy100Sell101_netReturnPositive() {
        BigDecimal entryReferencePrice = new BigDecimal("100.00");
        BigDecimal exitReferencePrice = new BigDecimal("101.00");

        BigDecimal netReturn = StockPortfolioService.calculateNetReturn(entryReferencePrice, exitReferencePrice);

        assertNotNull(netReturn);
        // 101/100 × 0.999 - 1 = 1.01 × 0.999 - 1 = 0.008999 > 0
        assertTrue(netReturn.compareTo(BigDecimal.ZERO) > 0,
                "净收益应为正,实际: " + netReturn);
    }

    @ParameterizedTest
    @DisplayName("检查入场价格偏离_偏离边界判定(恰好0.15%不取消/略超取消/价格相同不取消/下跌不取消)")
    @MethodSource("entryPriceDeviationCases")
    void checkEntryPriceDeviation_boundaryCases(BigDecimal signalPrice, BigDecimal entryPrice, boolean expected) {
        boolean result = StockPortfolioService.checkEntryPriceDeviation(signalPrice, entryPrice);
        assertEquals(expected, result);
    }

    /**
     * 入场价格偏离边界测试数据
     *
     * @return 测试参数流
     */
    private static Stream<org.junit.jupiter.params.provider.Arguments> entryPriceDeviationCases() {
        return Stream.of(
                // signal=100, entry=100.15 -> deviation = 0.0015 = 阈值,严格>才取消,不取消
                org.junit.jupiter.params.provider.Arguments.of(new BigDecimal("100.0000"), new BigDecimal("100.1500"), false),
                // signal=100, entry=100.16 -> deviation = 0.0016 > 0.0015,应取消
                org.junit.jupiter.params.provider.Arguments.of(new BigDecimal("100.0000"), new BigDecimal("100.1600"), true),
                // 价格相同不取消
                org.junit.jupiter.params.provider.Arguments.of(new BigDecimal("100.00"), new BigDecimal("100.00"), false),
                // 价格下跌不因偏离取消
                org.junit.jupiter.params.provider.Arguments.of(new BigDecimal("100.00"), new BigDecimal("90.00"), false)
        );
    }

    @Test
    @DisplayName("入场过期时间_信号桶10点开始,返回10点35分")
    void calculateEntryStaleAt_signalBar10Clock_returns10Clock35() {
        LocalDateTime signalBarStart = LocalDateTime.of(2026, 7, 24, 10, 0, 0);

        LocalDateTime staleAt = StockPortfolioService.calculateEntryStaleAt(signalBarStart);

        assertNotNull(staleAt);
        assertEquals(LocalDateTime.of(2026, 7, 24, 10, 35, 0), staleAt);
    }

    // ==================== 槽位生命周期(实例方法) ====================

    @Test
    @DisplayName("预留槽位_可用槽位预留后变为RESERVED且预留金额正确")
    void reserveSlot_availableSlot_becomesReservedAndAmountCorrect() {
        TornStockPortfolioSlotDO slot = buildAvailableSlot(1);
        BigDecimal reservedAmount = new BigDecimal("500000.00");
        Long batchId = 1001L;

        portfolioService.reserveSlot(slot, reservedAmount, batchId);

        assertEquals(StockSlotStatusEnum.RESERVED.getCode(), slot.getSlotStatus());
        assertEquals(0, reservedAmount.compareTo(slot.getReservedCash()));
        assertEquals(batchId, slot.getCurrentBatchId());
    }

    @Test
    @DisplayName("占用槽位_已预留槽位建仓后变为OCCUPIED且余款转为可用现金")
    void occupySlot_reservedSlot_becomesOccupiedAndDeductAvailableCash() {
        TornStockPortfolioSlotDO slot = buildReservedSlot(1, new BigDecimal("1000000.00"));
        Long quantity = 5000L;
        BigDecimal entryReferencePrice = new BigDecimal("100.00");
        Long batchId = 2001L;
        // actualCost = 5000 × 100 = 500000, remainingCash = 1000000 - 500000 = 500000
        BigDecimal expectedAvailable = new BigDecimal("500000.00");

        portfolioService.occupySlot(slot, quantity, entryReferencePrice, batchId);

        assertEquals(StockSlotStatusEnum.OCCUPIED.getCode(), slot.getSlotStatus());
        assertEquals(0, expectedAvailable.compareTo(slot.getAvailableCash()),
                "余款应转为可用现金,期望: " + expectedAvailable + ",实际: " + slot.getAvailableCash());
        assertEquals(0, BigDecimal.ZERO.compareTo(slot.getReservedCash()),
                "预留资金应清零");
        assertEquals(batchId, slot.getCurrentBatchId());
    }

    @Test
    @DisplayName("释放槽位_已预留槽位释放后变为AVAILABLE且返还预留金额")
    void releaseSlot_reservedSlot_becomesAvailableAndReturnsReservedAmount() {
        BigDecimal reservedAmount = new BigDecimal("800000.00");
        TornStockPortfolioSlotDO slot = buildReservedSlot(1, reservedAmount);
        // availableCash初始=2000000000,释放后 = 2000000000 + 800000
        BigDecimal expectedAvailable = new BigDecimal("2000000000.00").add(reservedAmount);

        portfolioService.releaseSlot(slot);

        assertEquals(StockSlotStatusEnum.AVAILABLE.getCode(), slot.getSlotStatus());
        assertEquals(0, expectedAvailable.compareTo(slot.getAvailableCash()),
                "可用现金应返还预留金额,期望: " + expectedAvailable + ",实际: " + slot.getAvailableCash());
        assertEquals(0, BigDecimal.ZERO.compareTo(slot.getReservedCash()),
                "预留资金应清零");
        assertNull(slot.getCurrentBatchId(), "批次ID应解绑");
    }

    @Test
    @DisplayName("结算槽位_已占用槽位卖出结算后变为AVAILABLE且卖出所得回槽")
    void settleSlot_occupiedSlot_becomesAvailableAndSellProceedsReturn() {
        TornStockPortfolioSlotDO slot = buildOccupiedSlot(1, new BigDecimal("1500000000.00"));
        long quantity = 5000L;
        BigDecimal exitReferencePrice = new BigDecimal("100.00");
        // sellProceeds = 5000 × 100 × 0.999 = 499500
        BigDecimal expectedProceeds = exitReferencePrice
                .multiply(BigDecimal.valueOf(quantity))
                .multiply(StockPortfolioService.SELL_FEE_RATE);
        BigDecimal expectedAvailable = new BigDecimal("1500000000.00").add(expectedProceeds);

        portfolioService.settleSlot(slot, quantity, exitReferencePrice);

        assertEquals(StockSlotStatusEnum.AVAILABLE.getCode(), slot.getSlotStatus());
        assertEquals(0, expectedAvailable.compareTo(slot.getAvailableCash()),
                "可用现金应增加卖出所得,期望: " + expectedAvailable + ",实际: " + slot.getAvailableCash());
        assertNull(slot.getCurrentBatchId(), "批次ID应解绑");
    }

    @Test
    @DisplayName("组合权益计算_3槽可用2槽占用,权益=现金合计+仓位市值合计")
    void calculateEquity_3SlotsAvailable2Occupied_equityCorrect() {
        TornStockPortfolioSlotDO slot1 = buildAvailableSlot(1); // available=20亿,reserved=0
        TornStockPortfolioSlotDO slot2 = buildAvailableSlot(2); // available=20亿,reserved=0
        TornStockPortfolioSlotDO slot3 = buildAvailableSlot(3); // available=20亿,reserved=0
        // slot4, slot5 已占用,可用现金减少
        TornStockPortfolioSlotDO slot4 = buildOccupiedSlot(4, new BigDecimal("1500000000.00"));
        TornStockPortfolioSlotDO slot5 = buildOccupiedSlot(5, new BigDecimal("1800000000.00"));
        List<TornStockPortfolioSlotDO> slots = List.of(slot1, slot2, slot3, slot4, slot5);

        // 仓位市值映射: key=槽位ID
        Map<Long, BigDecimal> batchMarketValues = Map.of(
                slot4.getId(), new BigDecimal("520000000.00"),
                slot5.getId(), new BigDecimal("310000000.00")
        );
        // 现金合计 = 20亿×3 + 15亿 + 18亿 = 93亿
        BigDecimal expectedCash = new BigDecimal("2000000000.00").multiply(BigDecimal.valueOf(3))
                .add(new BigDecimal("1500000000.00"))
                .add(new BigDecimal("1800000000.00"));
        BigDecimal expectedEquity = expectedCash
                .add(new BigDecimal("520000000.00"))
                .add(new BigDecimal("310000000.00"));

        BigDecimal equity = portfolioService.calculateEquity(slots, batchMarketValues);

        assertEquals(0, expectedEquity.compareTo(equity),
                "组合权益应为现金合计+仓位市值合计,期望: " + expectedEquity + ",实际: " + equity);
    }

    // ==================== Helper方法 ====================

    /**
     * 构建可用槽位(状态AVAILABLE,可用资金=20亿,预留=0,无批次)
     */
    private TornStockPortfolioSlotDO buildAvailableSlot(int slotNo) {
        TornStockPortfolioSlotDO slot = new TornStockPortfolioSlotDO();
        slot.setId((long) slotNo);
        slot.setPortfolioCode(StockPortfolioService.PORTFOLIO_CODE);
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
     * 构建已预留槽位(状态RESERVED,可用资金=20亿,预留金额=参数值)
     */
    private TornStockPortfolioSlotDO buildReservedSlot(int slotNo, BigDecimal reservedAmount) {
        TornStockPortfolioSlotDO slot = buildAvailableSlot(slotNo);
        slot.setReservedCash(reservedAmount);
        slot.setCurrentBatchId(1001L);
        slot.setSlotStatus(StockSlotStatusEnum.RESERVED.getCode());
        return slot;
    }

    /**
     * 构建已占用槽位(状态OCCUPIED,可用现金=参数值,预留=0)
     */
    private TornStockPortfolioSlotDO buildOccupiedSlot(int slotNo, BigDecimal availableCash) {
        TornStockPortfolioSlotDO slot = buildAvailableSlot(slotNo);
        slot.setAvailableCash(availableCash);
        slot.setReservedCash(BigDecimal.ZERO);
        slot.setCurrentBatchId(2001L);
        slot.setSlotStatus(StockSlotStatusEnum.OCCUPIED.getCode());
        return slot;
    }
}
