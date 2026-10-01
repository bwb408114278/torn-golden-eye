package pn.torn.goldeneye.torn.service.stocks.alert.settlement;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * α正式仓年度结算纯计算器 - 只做金额与区间派生量的计算,不访问DAO、不读时钟。
 *
 * <p>口径固定为"方案C:只记账不划转":
 * <ul>
 *   <li>年末边界权益 {@code E_y} 由调用方传入(必须来自既有权益口径 {@code PortfolioEquityCalculator});</li>
 *   <li>边界持仓可变现市值按恒等式 {@code E_y - 现金 - 预留} 派生,保证数据库层守恒约束恒成立;</li>
 *   <li>本年度基准 {@code B_y = 初始资金 + 累计已提取},本年提取额 {@code W_y = E_y - 累计已提取 - 初始资金},
 *       累计已提取 {@code C_y = C_{y-1} + W_y},收益率 {@code R_y = E_y / B_y - 1};</li>
 *   <li>提取是账面科目:{@code W_y < 0} 表示账面利润被年度亏损回撤,按原样记录,禁止用任何现金调整补足。</li>
 * </ul>
 *
 * <p>年化折算只在首个不完整年度计算并展示(ACT/365):完整年度的区间收益即年化口径,本类不再落年化值;
 * {@code N < 1} 或 {@code 1 + R_interval <= 0} 时年化不适用,落null由渲染层标注"样本不足"。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@Component
public class StockAnnualSettlementCalculator {
    /**
     * 金额保留小数位
     */
    private static final int MONEY_SCALE_DIGITS = 2;
    /**
     * 比率保留小数位
     */
    private static final int RATE_SCALE_DIGITS = 18;
    /**
     * 一年的天数(ACT/365的分子)
     */
    private static final int ACT_DAYS_PER_YEAR = 365;
    /**
     * 一个数值常量:1
     */
    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * 计算年度结算的全部金额与区间派生量。
     *
     * @param input 结算输入(初始资金、累计已提取、年末边界权益、边界现金与预留、首笔入场日、被结算年与边界时点)
     * @return 结算结果;金额均为2位小数,比率为18位小数
     * @throws IllegalArgumentException 本年度基准非正(累计提取与初始资金组合异常)或首笔入场日为空时抛出
     */
    public SettlementResult calculate(SettlementInput input) {
        if (input.firstEntryDate() == null) {
            throw new IllegalArgumentException("年度结算首笔入场日为空,无法计算覆盖率");
        }
        BigDecimal initialCash = money(input.initialCash());
        BigDecimal cumulativeBefore = money(input.cumulativeExtractedBefore());
        BigDecimal closingCash = money(input.closingCash());
        BigDecimal closingReserved = money(input.closingReserved());
        BigDecimal closingEquity = money(input.closingEquity());

        BigDecimal openingEquity = money(initialCash.add(cumulativeBefore));
        if (openingEquity.signum() <= 0) {
            throw new IllegalArgumentException("年度结算本年度基准非正,无法计算收益率: openingEquity=" + openingEquity);
        }

        BigDecimal closingMarketValue = money(closingEquity.subtract(closingCash).subtract(closingReserved));
        BigDecimal extractedAmount = money(closingEquity.subtract(cumulativeBefore).subtract(initialCash));
        BigDecimal cumulativeAfter = money(cumulativeBefore.add(extractedAmount));
        BigDecimal yearReturn = closingEquity.divide(openingEquity, RATE_SCALE_DIGITS, RoundingMode.HALF_UP)
                .subtract(ONE);

        // 结算区间只能落在被结算自然年内:首笔入场日早于被结算年1月1日时按1月1日钳制,避免重复计入已结算年度
        LocalDate yearStart = LocalDate.of(input.settleYear(), 1, 1);
        LocalDate coverageStartDate = input.firstEntryDate().isBefore(yearStart) ? yearStart : input.firstEntryDate();
        int coverageDays = (int) ChronoUnit.DAYS.between(coverageStartDate, input.boundaryTime().toLocalDate());
        boolean partialYear = coverageDays < daysOfYear(input.settleYear());
        BigDecimal annualizedReturn = partialYear ? annualize(yearReturn, coverageDays) : null;
        return new SettlementResult(openingEquity, closingMarketValue, extractedAmount, cumulativeAfter,
                yearReturn, coverageDays, partialYear, annualizedReturn);
    }

    /**
     * 按 ACT/365 折算区间收益率。
     *
     * @param intervalReturn 区间收益率
     * @param coverageDays   区间自然日数
     * @return 年化收益率(18位小数,HALF_UP);{@code coverageDays < 1} 或 {@code 1 + 区间收益 <= 0} 时返回null
     */
    private BigDecimal annualize(BigDecimal intervalReturn, int coverageDays) {
        if (coverageDays < 1) {
            return null;
        }
        double growth = ONE.add(intervalReturn).doubleValue();
        if (growth <= 0) {
            return null;
        }
        double annualized = Math.pow(growth, (double) ACT_DAYS_PER_YEAR / coverageDays) - 1;
        return BigDecimal.valueOf(annualized).setScale(RATE_SCALE_DIGITS, RoundingMode.HALF_UP);
    }

    /**
     * 计算指定自然年的自然日总数。
     *
     * @param settleYear 被结算的自然年
     * @return 365或366
     */
    private int daysOfYear(int settleYear) {
        return LocalDate.of(settleYear, 12, 31).getDayOfYear();
    }

    /**
     * 将金额归一到2位小数,null按0处理。
     *
     * @param amount 金额
     * @return 2位小数的金额
     */
    private BigDecimal money(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(MONEY_SCALE_DIGITS, RoundingMode.HALF_UP);
    }

    /**
     * 年度结算输入。
     *
     * @param initialCash               初始资金(报表基准)
     * @param cumulativeExtractedBefore 本次结算前累计已提取(首个年度为0)
     * @param closingEquity             年末边界权益(来自既有权益口径)
     * @param closingCash               边界可用现金合计
     * @param closingReserved           边界预留资金合计
     * @param firstEntryDate            首笔α批次入场日;早于被结算年1月1日时按被结算年1月1日钳制
     * @param settleYear                被结算的自然年
     * @param boundaryTime              年度边界时点(次年1月1日00:00)
     */
    public record SettlementInput(
            BigDecimal initialCash,
            BigDecimal cumulativeExtractedBefore,
            BigDecimal closingEquity,
            BigDecimal closingCash,
            BigDecimal closingReserved,
            LocalDate firstEntryDate,
            int settleYear,
            LocalDateTime boundaryTime) {
    }

    /**
     * 年度结算计算结果。
     *
     * @param openingEquity            本年度基准
     * @param closingMarketValue       边界持仓可变现市值(按权益恒等式派生)
     * @param extractedAmount          本年提取额(可为负)
     * @param cumulativeExtractedAfter 截至本年末累计已提取
     * @param yearReturn               年度收益率
     * @param coverageDays             区间自然日数
     * @param partialYear              是否不完整年度(试运行)
     * @param annualizedReturn         年化折算(仅不完整年度计算;不适用时为null)
     */
    public record SettlementResult(
            BigDecimal openingEquity,
            BigDecimal closingMarketValue,
            BigDecimal extractedAmount,
            BigDecimal cumulativeExtractedAfter,
            BigDecimal yearReturn,
            int coverageDays,
            boolean partialYear,
            BigDecimal annualizedReturn) {
    }
}
