package pn.torn.goldeneye.torn.service.stocks.alert.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.service.stocks.alert.settlement.StockAnnualSettlementCalculator.SettlementInput;
import pn.torn.goldeneye.torn.service.stocks.alert.settlement.StockAnnualSettlementCalculator.SettlementResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * α年度结算纯计算器测试,覆盖方案C的提取恒等式、亏损年度、累计提取与年化折算口径。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@DisplayName("α年度结算计算器测试")
class StockAnnualSettlementCalculatorTest {
    /**
     * 初始资金(报表基准)
     */
    private static final BigDecimal INITIAL_CASH = new BigDecimal("10000000000.00");
    /**
     * 测试年度边界时点
     */
    private static final LocalDateTime BOUNDARY_TIME = LocalDateTime.of(2027, 1, 1, 0, 0);

    private final StockAnnualSettlementCalculator calculator = new StockAnnualSettlementCalculator();

    @Test
    @DisplayName("首个不完整年度_提取恒等式成立且区间区间收益按区间日数折算年化")
    void calculate_firstPartialYear_satisfiesExtractionIdentities() {
        SettlementResult result = calculator.calculate(new SettlementInput(INITIAL_CASH, BigDecimal.ZERO,
                new BigDecimal("11250000000.00"), new BigDecimal("1000000000.00"), new BigDecimal("250000000.00"),
                LocalDate.of(2026, 9, 17), 2026, BOUNDARY_TIME));

        assertEquals(0, new BigDecimal("10000000000.00").compareTo(result.openingEquity()),
                "本年度基准=初始资金+累计已提取");
        assertEquals(0, new BigDecimal("10000000000.00").compareTo(result.closingMarketValue()),
                "持仓可变现市值=权益-现金-预留");
        assertEquals(0, new BigDecimal("1250000000.00").compareTo(result.extractedAmount()));
        assertEquals(0, new BigDecimal("1250000000.00").compareTo(result.cumulativeExtractedAfter()));
        assertEquals(0, new BigDecimal("0.125").compareTo(result.yearReturn()));
        assertEquals(106, result.coverageDays());
        assertTrue(result.partialYear(), "首个年度为不完整年度");
        assertNotNull(result.annualizedReturn(), "不完整年度必须给出年化折算");
        assertTrue(result.annualizedReturn().compareTo(result.yearReturn()) > 0, "年化折算应大于区间收益");
    }

    @Test
    @DisplayName("亏损年度_本年提取额为负且累计提取允许下降")
    void calculate_lossYear_extractedAmountIsNegative() {
        SettlementResult result = calculator.calculate(new SettlementInput(INITIAL_CASH,
                new BigDecimal("1250000000.00"), new BigDecimal("10750000000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.of(2026, 9, 17), 2026, BOUNDARY_TIME));

        assertEquals(0, new BigDecimal("11250000000.00").compareTo(result.openingEquity()));
        assertEquals(0, new BigDecimal("-500000000.00").compareTo(result.extractedAmount()));
        assertEquals(0, new BigDecimal("750000000.00").compareTo(result.cumulativeExtractedAfter()),
                "累计提取必须按年度亏损回撤,禁止用现金调整补足");
        assertEquals(0, new BigDecimal("-0.044444444444444444").compareTo(result.yearReturn().setScale(18)));
    }

    @Test
    @DisplayName("完整年度_区间收益即年化口径且不再落年化折算")
    void calculate_completeYear_annualizedReturnIsNull() {
        SettlementResult result = calculator.calculate(new SettlementInput(INITIAL_CASH, BigDecimal.ZERO,
                new BigDecimal("10500000000.00"), new BigDecimal("10500000000.00"), BigDecimal.ZERO,
                LocalDate.of(2026, 1, 1), 2026, BOUNDARY_TIME));

        assertEquals(365, result.coverageDays());
        assertFalse(result.partialYear());
        assertNull(result.annualizedReturn(), "完整年度不落年化折算,避免同一数字重复展示");
        assertEquals(0, new BigDecimal("0.05").compareTo(result.yearReturn()));
    }

    @Test
    @DisplayName("区间不足一天_年化不适用且仍保留区间收益与提取额")
    void calculate_coverageLessThanOneDay_annualizedReturnIsNull() {
        SettlementResult result = calculator.calculate(new SettlementInput(INITIAL_CASH, BigDecimal.ZERO,
                new BigDecimal("10100000000.00"), new BigDecimal("10100000000.00"), BigDecimal.ZERO,
                LocalDate.of(2027, 1, 1), 2026, BOUNDARY_TIME));

        assertEquals(0, result.coverageDays());
        assertNull(result.annualizedReturn(), "样本不足时年化必须为空,不得外推填充");
        assertEquals(0, new BigDecimal("100000000.00").compareTo(result.extractedAmount()));
    }

    @Test
    @DisplayName("本年度基准非正_拒绝以非法基准计算收益率")
    void calculate_nonPositiveOpeningEquity_throwsIllegalArgument() {
        SettlementInput input = new SettlementInput(INITIAL_CASH, new BigDecimal("-10000000000.00"),
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.of(2026, 9, 17), 2026,
                BOUNDARY_TIME);

        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(input));
    }

    @Test
    @DisplayName("区间起点缺失_拒绝计算覆盖率")
    void calculate_missingRangeStart_throwsIllegalArgument() {
        SettlementInput input = new SettlementInput(INITIAL_CASH, BigDecimal.ZERO, new BigDecimal("100.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, null, 2026, BOUNDARY_TIME);

        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(input));
    }
}
