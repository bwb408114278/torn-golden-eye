package pn.torn.goldeneye.torn.service.stocks.alert.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 股票通知文案格式化测试,覆盖共享金额与收益率格式化的唯一实现。
 *
 * @author Bai
 * @version 1.6.6
 * @since 2026.10.01
 */
@DisplayName("股票通知文案格式化测试")
class StockNoticeTextFormatTest {

    @Test
    @DisplayName("金额格式化_十亿单位保留两位小数且四舍五入")
    void formatBillion_billionUnitWithTwoDigitsAndHalfUp() {
        assertEquals("10.00b", StockNoticeTextFormat.formatBillion(new BigDecimal("10000000000.00")));
        assertEquals("1.25b", StockNoticeTextFormat.formatBillion(new BigDecimal("1250000000.00")));
        assertEquals("1.05b", StockNoticeTextFormat.formatBillion(new BigDecimal("1049999999.00")));
        assertEquals("1.00b", StockNoticeTextFormat.formatBillion(new BigDecimal("999999999.00")));
    }

    @Test
    @DisplayName("金额格式化_不足十亿显示0.00b且空值安全")
    void formatBillion_belowBillionAndNull() {
        assertEquals("0.00b", StockNoticeTextFormat.formatBillion(new BigDecimal("79246.00")));
        assertEquals("0.00b", StockNoticeTextFormat.formatBillion(BigDecimal.ZERO));
        assertEquals("0.00b", StockNoticeTextFormat.formatBillion(null));
    }

    @Test
    @DisplayName("金额格式化_负数保留负号且不为正数补正号")
    void formatBillion_negativeKeepsMinusSignAndPositiveHasNoSign() {
        assertEquals("-0.42b", StockNoticeTextFormat.formatBillion(new BigDecimal("-420000000.00")));
        String positive = StockNoticeTextFormat.formatBillion(new BigDecimal("1250000000.00"));
        assertFalse(positive.startsWith("+"), "金额不带正号,仅收益率带符号");
    }
}
