package pn.torn.goldeneye.torn.service.stocks.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockCloseTypeEnum;
import pn.torn.goldeneye.constants.torn.enums.stocks.portfolio.StockFormalReasonEnum;
import pn.torn.goldeneye.repository.model.torn.stocks.portfolio.TornStockVirtualBatchDO;
import pn.torn.goldeneye.torn.service.stocks.alert.StockBatchExitService.ExitEvaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 股票批次退出评估服务单元测试 - 覆盖目标、风险、时间、区间四种退出规则
 * <p>
 * 验证 {@link StockBatchExitService#evaluateExit} 的退出判定优先级与边界条件:
 * 目标退出(netReturn >= +0.8%)、风险退出(netReturn <= -1.5%)、时间退出(持有 >= 14天)、
 * 区间恢复退出(RANGE_LOWER_BUY + netReturn > 0 + position30 >= 0.60)。
 * 所有净收益计算统一扣除0.1%卖出手续费。
 *
 * @author Bai
 * @version 1.2.12
 * @since 2026.07.24
 */
@DisplayName("股票批次退出评估服务测试")
class StockBatchExitServiceTest {

    private StockBatchExitService exitService;

    /**
     * 入场参考价 - 固定100元,便于计算
     */
    private static final BigDecimal ENTRY_PRICE = new BigDecimal("100.00");
    /**
     * 区间下沿买入策略标识
     */
    private static final String RANGE_LOWER_BUY = StockBatchExitService.RANGE_LOWER_BUY_STRATEGY;
    /**
     * 非区间策略标识
     */
    private static final String NON_RANGE_STRATEGY = "DEEP_MEAN_REVERSION_BUY";

    @BeforeEach
    void setUp() {
        exitService = new StockBatchExitService();
    }

    // ==================== 目标退出 ====================

    @Test
    @DisplayName("退出评估_净收益恰好+0.8%时触发目标退出")
    void evaluateExit_netReturnExactlyPlus0p8_triggersTargetExit() {
        // 直接用checkTargetExit验证边界: netReturn恰好等于0.008阈值
        ExitEvaluation result = exitService.checkTargetExit(new BigDecimal("0.008"));

        assertTrue(result.shouldExit(), "净收益恰好+0.8%应触发目标退出");
        assertEquals(StockCloseTypeEnum.CLOSED_TARGET, result.closeType());
        assertEquals(StockFormalReasonEnum.SELL_TARGET_REACHED.getCode(), result.reasonCode());
    }

    @Test
    @DisplayName("退出评估_净收益略高于+0.8%时触发目标退出")
    void evaluateExit_netReturnSlightlyAbovePlus0p8_triggersTargetExit() {
        TornStockVirtualBatchDO batch = buildOpenBatch(ENTRY_PRICE, LocalDateTime.now(), null);
        BigDecimal currentPrice = calcPriceForNetReturn(ENTRY_PRICE, new BigDecimal("0.009"));

        ExitEvaluation result = exitService.evaluateExit(batch, currentPrice, null, null, null, LocalDateTime.now());

        assertTrue(result.shouldExit(), "应触发退出");
        assertEquals(StockCloseTypeEnum.CLOSED_TARGET, result.closeType());
    }

    @Test
    @DisplayName("退出评估_净收益略低于+0.8%时不触发目标退出")
    void evaluateExit_netReturnSlightlyBelowPlus0p8_notTriggerTargetExit() {
        TornStockVirtualBatchDO batch = buildOpenBatch(ENTRY_PRICE, LocalDateTime.now(), NON_RANGE_STRATEGY);
        BigDecimal currentPrice = calcPriceForNetReturn(ENTRY_PRICE, new BigDecimal("0.007"));

        ExitEvaluation result = exitService.evaluateExit(batch, currentPrice, null, null, null, LocalDateTime.now());

        assertFalse(result.shouldExit(), "净收益低于0.8%且无其他退出条件,不应退出");
    }

    // ==================== 风险退出 ====================

    @Test
    @DisplayName("退出评估_净收益恰好-1.5%时触发风险退出")
    void evaluateExit_netReturnExactlyMinus1p5_triggersRiskExit() {
        // 直接用checkRiskExit验证边界: netReturn恰好等于-0.015阈值
        ExitEvaluation result = exitService.checkRiskExit(new BigDecimal("-0.015"));

        assertTrue(result.shouldExit(), "净收益恰好-1.5%应触发风险退出");
        assertEquals(StockCloseTypeEnum.CLOSED_RISK, result.closeType());
        assertEquals(StockFormalReasonEnum.SELL_HARD_RISK.getCode(), result.reasonCode());
    }

    @Test
    @DisplayName("退出评估_净收益略低于-1.5%时触发风险退出")
    void evaluateExit_netReturnSlightlyBelowMinus1p5_triggersRiskExit() {
        TornStockVirtualBatchDO batch = buildOpenBatch(ENTRY_PRICE, LocalDateTime.now(), null);
        BigDecimal currentPrice = calcPriceForNetReturn(ENTRY_PRICE, new BigDecimal("-0.016"));

        ExitEvaluation result = exitService.evaluateExit(batch, currentPrice, null, null, null, LocalDateTime.now());

        assertTrue(result.shouldExit(), "应触发退出");
        assertEquals(StockCloseTypeEnum.CLOSED_RISK, result.closeType());
    }

    @Test
    @DisplayName("退出评估_净收益略高于-1.5%时不触发风险退出")
    void evaluateExit_netReturnSlightlyAboveMinus1p5_notTriggerRiskExit() {
        TornStockVirtualBatchDO batch = buildOpenBatch(ENTRY_PRICE, LocalDateTime.now(), NON_RANGE_STRATEGY);
        BigDecimal currentPrice = calcPriceForNetReturn(ENTRY_PRICE, new BigDecimal("-0.014"));

        ExitEvaluation result = exitService.evaluateExit(batch, currentPrice, null, null, null, LocalDateTime.now());

        assertFalse(result.shouldExit(), "净收益高于-1.5%且无其他退出条件,不应退出");
    }

    // ==================== 时间退出 ====================

    @Test
    @DisplayName("退出评估_持有恰好14天时触发时间退出")
    void evaluateExit_holdExactly14Days_triggersTimeExit() {
        // entryTime = 14天前,当前价不触发目标/风险退出
        LocalDateTime entryTime = LocalDateTime.now().minusDays(14);
        TornStockVirtualBatchDO batch = buildOpenBatch(ENTRY_PRICE, entryTime, NON_RANGE_STRATEGY);
        // netReturn = -0.001,不触发目标/风险
        ExitEvaluation result = exitService.evaluateExit(batch, ENTRY_PRICE, null, null, null, LocalDateTime.now());

        assertTrue(result.shouldExit(), "持有14天应触发退出");
        assertEquals(StockCloseTypeEnum.CLOSED_TIME, result.closeType());
        assertEquals(StockFormalReasonEnum.SELL_MAX_HOLD.getCode(), result.reasonCode());
    }

    @Test
    @DisplayName("退出评估_持有13天时不触发时间退出")
    void evaluateExit_hold13Days_notTriggerTimeExit() {
        LocalDateTime entryTime = LocalDateTime.now().minusDays(13);
        TornStockVirtualBatchDO batch = buildOpenBatch(ENTRY_PRICE, entryTime, NON_RANGE_STRATEGY);
        // netReturn = -0.001
        ExitEvaluation result = exitService.evaluateExit(batch, ENTRY_PRICE, null, null, null, LocalDateTime.now());

        assertFalse(result.shouldExit(), "持有13天不应退出");
    }

    // ==================== 区间恢复退出 ====================

    @Test
    @DisplayName("退出评估_区间策略净收益正且position30=0.60时触发区间退出")
    void evaluateExit_rangeStrategyNetReturnPositiveAndPosition30Is0p60_triggersRangeExit() {
        TornStockVirtualBatchDO batch = buildOpenBatch(ENTRY_PRICE, LocalDateTime.now(), RANGE_LOWER_BUY);
        BigDecimal currentPrice = calcPriceForNetReturn(ENTRY_PRICE, new BigDecimal("0.003")); // netReturn > 0
        BigDecimal position30 = new BigDecimal("0.60");
        BigDecimal low30d = new BigDecimal("90.00");
        BigDecimal high30d = new BigDecimal("110.00");

        ExitEvaluation result = exitService.evaluateExit(batch, currentPrice, position30, low30d, high30d, LocalDateTime.now());

        assertTrue(result.shouldExit(), "应触发退出");
        assertEquals(StockCloseTypeEnum.CLOSED_RANGE, result.closeType());
        assertEquals(StockFormalReasonEnum.SELL_RANGE_RECOVERED.getCode(), result.reasonCode());
    }

    @Test
    @DisplayName("退出评估_区间策略净收益为0时不触发区间退出")
    void evaluateExit_rangeStrategyNetReturnZero_notTriggerRangeExit() {
        TornStockVirtualBatchDO batch = buildOpenBatch(ENTRY_PRICE, LocalDateTime.now(), RANGE_LOWER_BUY);
        // netReturn = 0 -> currentPrice/100×0.999-1 = 0 -> currentPrice = 100/0.999
        BigDecimal currentPrice = calcPriceForNetReturn(ENTRY_PRICE, BigDecimal.ZERO);
        BigDecimal position30 = new BigDecimal("0.80");
        BigDecimal low30d = new BigDecimal("90.00");
        BigDecimal high30d = new BigDecimal("110.00");

        ExitEvaluation result = exitService.evaluateExit(batch, currentPrice, position30, low30d, high30d, LocalDateTime.now());

        assertFalse(result.shouldExit(), "净收益为0不满足区间退出条件,不应退出");
    }

    @Test
    @DisplayName("退出评估_区间策略high30等于low30时不触发区间退出(fail-closed)")
    void evaluateExit_rangeStrategyHigh30EqualsLow30_notTriggerRangeExit() {
        TornStockVirtualBatchDO batch = buildOpenBatch(ENTRY_PRICE, LocalDateTime.now(), RANGE_LOWER_BUY);
        BigDecimal currentPrice = calcPriceForNetReturn(ENTRY_PRICE, new BigDecimal("0.005")); // netReturn > 0
        BigDecimal position30 = new BigDecimal("0.80");
        BigDecimal low30d = new BigDecimal("100.00");
        BigDecimal high30d = new BigDecimal("100.00"); // == low30d

        ExitEvaluation result = exitService.evaluateExit(batch, currentPrice, position30, low30d, high30d, LocalDateTime.now());

        assertFalse(result.shouldExit(), "high30==low30时fail-closed,不应退出");
    }

    @Test
    @DisplayName("退出评估_非区间策略不触发区间退出")
    void evaluateExit_nonRangeStrategy_notTriggerRangeExit() {
        TornStockVirtualBatchDO batch = buildOpenBatch(ENTRY_PRICE, LocalDateTime.now(), NON_RANGE_STRATEGY);
        BigDecimal currentPrice = calcPriceForNetReturn(ENTRY_PRICE, new BigDecimal("0.005")); // netReturn > 0
        BigDecimal position30 = new BigDecimal("0.80");
        BigDecimal low30d = new BigDecimal("90.00");
        BigDecimal high30d = new BigDecimal("110.00");

        ExitEvaluation result = exitService.evaluateExit(batch, currentPrice, position30, low30d, high30d, LocalDateTime.now());

        assertFalse(result.shouldExit(), "非区间策略不触发区间退出,不应退出");
    }

    // ==================== 无退出条件 ====================

    @Test
    @DisplayName("退出评估_无任何退出条件满足时返回shouldExit为false")
    void evaluateExit_noExitConditionMet_returnsShouldExitFalse() {
        TornStockVirtualBatchDO batch = buildOpenBatch(ENTRY_PRICE, LocalDateTime.now().minusDays(5), NON_RANGE_STRATEGY);
        BigDecimal currentPrice = ENTRY_PRICE; // netReturn = -0.001,不触发目标/风险
        BigDecimal position30 = new BigDecimal("0.50");
        BigDecimal low30d = new BigDecimal("90.00");
        BigDecimal high30d = new BigDecimal("110.00");

        ExitEvaluation result = exitService.evaluateExit(batch, currentPrice, position30, low30d, high30d, LocalDateTime.now());

        assertFalse(result.shouldExit(), "无退出条件满足时不应退出");
        assertNull(result.closeType(), "shouldExit=false时closeType应为null");
        assertEquals(StockFormalReasonEnum.HOLD_NO_EXIT_TRIGGERED.getCode(), result.reasonCode());
        assertNotNull(result.reason(), "reason应描述未退出原因");
    }

    // ==================== Helper方法 ====================

    /**
     * 构建OPEN状态批次
     *
     * @param entryReferencePrice 入场参考价
     * @param entryTime           入场时间
     * @param primaryStrategy     主策略(可为null)
     * @return 构建好的批次DO
     */
    private TornStockVirtualBatchDO buildOpenBatch(BigDecimal entryReferencePrice,
                                                   LocalDateTime entryTime,
                                                   String primaryStrategy) {
        TornStockVirtualBatchDO batch = new TornStockVirtualBatchDO();
        batch.setId(1L);
        batch.setBatchNo("B20260724001");
        batch.setLedgerType("FORMAL");
        batch.setStocksId(1001);
        batch.setStocksShortname("测试股票");
        batch.setPrimaryStrategy(primaryStrategy);
        batch.setBatchStatus("OPEN");
        batch.setEntryTime(entryTime);
        batch.setEntryReferencePrice(entryReferencePrice);
        batch.setQuantity(10000L);
        batch.setInvestedCash(entryReferencePrice.multiply(BigDecimal.valueOf(10000L)));
        return batch;
    }

    /**
     * 根据期望的净收益率反算当前价格
     * <p>
     * netReturn = currentPrice / entryPrice × 0.999 - 1
     * => currentPrice = (netReturn + 1) / 0.999 × entryPrice
     *
     * @param entryPrice 入场参考价
     * @param netReturn  期望净收益率
     * @return 对应的当前价格
     */
    private BigDecimal calcPriceForNetReturn(BigDecimal entryPrice, BigDecimal netReturn) {
        return netReturn.add(BigDecimal.ONE)
                .divide(StockBatchExitService.SELL_FEE_RATE, 18, RoundingMode.HALF_UP)
                .multiply(entryPrice);
    }
}
