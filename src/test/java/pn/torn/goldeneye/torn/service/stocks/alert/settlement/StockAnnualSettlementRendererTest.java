package pn.torn.goldeneye.torn.service.stocks.alert.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pn.torn.goldeneye.torn.service.stocks.alert.settlement.StockAnnualSettlementRenderer.AnnualReportData;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 年度报告渲染器测试,覆盖已定稿文案模板、X.XXb金额口径、试运行年化与亏损说明。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@DisplayName("年度报告渲染器测试")
class StockAnnualSettlementRendererTest {

    private final StockAnnualSettlementRenderer renderer = new StockAnnualSettlementRenderer();

    @Test
    @DisplayName("试运行年度_按已定稿模板逐行渲染且金额不带正号")
    void render_partialYear_matchesFrozenTemplate() {
        AnnualReportData data = new AnnualReportData(2026, true, LocalDate.of(2026, 9, 17), 106,
                new BigDecimal("0.125"), new BigDecimal("0.4567"), new BigDecimal("1250000000.00"),
                new BigDecimal("1250000000.00"));

        String report = renderer.render(data);
        String ls = System.lineSeparator();

        assertEquals(String.join(ls,
                        "【VIP股票 α 年度报告 · 2026】",
                        "",
                        "年度状态：试运行（2026-09-17 起，共 106 天）",
                        "区间收益：+12.50%",
                        "年化折算：+45.67%（试运行，仅供参考）",
                        "",
                        "本年账面利润：1.25b",
                        "累计账面利润：1.25b",
                        "",
                        "本报告为系统内部虚拟组合记录，不构成投资建议；账面利润为记账口径，资金仍在槽内继续复利。"),
                report);
    }

    @Test
    @DisplayName("完整年度_只展示区间收益且省略年化行")
    void render_completeYear_omitsAnnualizedLine() {
        AnnualReportData data = new AnnualReportData(2027, false, LocalDate.of(2027, 1, 1), 365,
                new BigDecimal("0.05"), null, new BigDecimal("500000000.00"), new BigDecimal("1750000000.00"));

        String report = renderer.render(data);

        assertTrue(report.contains("年度状态：完整年度（365 天）"));
        assertTrue(report.contains("区间收益：+5.00%"));
        assertFalse(report.contains("年化折算"), "完整年度区间收益即年化,不得重复展示");
    }

    @Test
    @DisplayName("年化不适用_明确标注样本不足而不展示年化")
    void render_annualizedUnavailable_statesSampleInsufficient() {
        AnnualReportData data = new AnnualReportData(2026, true, LocalDate.of(2026, 12, 31), 0,
                new BigDecimal("0.01"), null, new BigDecimal("100000000.00"), new BigDecimal("100000000.00"));

        String report = renderer.render(data);

        assertTrue(report.contains("年化折算：样本不足，不展示年化"));
    }

    @Test
    @DisplayName("亏损年度_追加账面回撤说明且金额保留负号")
    void render_negativeProfit_appendsDrawdownNote() {
        AnnualReportData data = new AnnualReportData(2026, false, LocalDate.of(2026, 1, 1), 365,
                new BigDecimal("-0.042"), null, new BigDecimal("-420000000.00"), new BigDecimal("-420000000.00"));

        String report = renderer.render(data);

        assertTrue(report.contains("本年账面利润：-0.42b"));
        assertTrue(report.contains("区间收益：-4.20%"));
        assertTrue(report.contains("注：本年账面利润为负表示账面利润被年度亏损回撤，非资金回补。"));
    }

    @Test
    @DisplayName("正账面利润_不出现正号且不追加回撤说明")
    void render_positiveProfit_hasNoPlusSignAndNoNote() {
        AnnualReportData data = new AnnualReportData(2026, false, LocalDate.of(2026, 1, 1), 365,
                new BigDecimal("0.1"), null, new BigDecimal("1000000000.00"), BigDecimal.ZERO);

        String report = renderer.render(data);

        assertTrue(report.contains("本年账面利润：1.00b"));
        assertFalse(report.contains("本年账面利润：+"));
        assertFalse(report.contains("非资金回补"));
    }
}
